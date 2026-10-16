package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.config.QueueConfig;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.exception.SeckillStockExhaustedException;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SeckillKeys;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 秒杀下单。
 *
 * <p><b>请求线程只碰 Redis，绝不碰 MySQL</b>：Lua 原子预检（查库存 + 查重 + 扣减 + 记录）
 * 通过后，把订单丢进 RabbitMQ 就返回。真正的落库由消费者异步完成 —— 这是异步削峰的全部意义。
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder>
        implements IVoucherOrderService {

    /** 等待 broker 确认的最长时间。本机 broker 实际是亚毫秒级，这个值只是兜底 */
    private static final long CONFIRM_TIMEOUT_MS = 500L;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RabbitTemplate rabbitTemplate;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    private static final DefaultRedisScript<Long> ROLLBACK_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);

        ROLLBACK_SCRIPT = new DefaultRedisScript<>();
        ROLLBACK_SCRIPT.setLocation(new ClassPathResource("seckill_rollback.lua"));
        ROLLBACK_SCRIPT.setResultType(Long.class);
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        Long userId = user.getId();
        long orderId = redisIdWorker.nextId("order");

        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                List.of(SeckillKeys.stock(voucherId), SeckillKeys.orders(voucherId)),
                userId.toString(), String.valueOf(orderId));

        // 旧版把 null 直接当成 0（成功）继续往下走 —— 脚本执行失败与成功无法区分，
        // 连脚本压根没跑通都会发 MQ 消息。这里必须显式区分。
        if (result == null) {
            log.error("秒杀 Lua 返回 null，脚本执行异常 voucherId={} userId={}", voucherId, userId);
            return Result.fail("系统繁忙，请稍后再试");
        }

        int r = result.intValue();
        if (r == -1) {
            // 旧版把这个分支也报成"不能重复下单"，完全误导。它其实是库存未初始化。
            log.error("秒杀库存未初始化，需从 DB 重建 voucherId={}", voucherId);
            return Result.fail("秒杀尚未开始或已结束");
        }
        if (r == 1) {
            return Result.fail("库存不足");
        }
        if (r == 2) {
            return Result.fail("不能重复下单");
        }

        // r == 0：Redis 侧已扣减并记录。接下来必须保证消息真的进了 broker，
        // 否则这个用户的库存白扣、且他再也抢不到（Lua 会返回 2）。
        VoucherOrder order = new VoucherOrder()
                .setId(orderId)
                .setUserId(userId)
                .setVoucherId(voucherId);

        if (!sendOrderMessage(order)) {
            rollbackSeckill(voucherId, userId, orderId);
            return Result.fail("系统繁忙，请稍后再试");
        }

        return Result.ok(orderId);
    }

    /**
     * 发送订单消息并<b>同步等待</b> broker 确认。
     *
     * <p>为什么要等：{@code convertAndSend} 默认是 fire-and-forget —— 只要 TCP 写成功就返回，
     * broker 是否真的持久化了、消息是否路由到了队列，发送方一概不知。开启
     * {@code publisher-confirm-type: correlated} 后可以拿到 {@link CorrelationData} 的 future，
     * 从而把"消息确实落地了"变成可判断的事实。
     *
     * <p>代价是热路径上多了最多 {@link #CONFIRM_TIMEOUT_MS} 毫秒。本机 broker 实测亚毫秒，
     * 这个超时是安全网而非常态开销。
     *
     * @return true 表示 broker 已确认且消息可路由
     */
    private boolean sendOrderMessage(VoucherOrder order) {
        CorrelationData correlationData = new CorrelationData(String.valueOf(order.getId()));
        try {
            rabbitTemplate.convertAndSend(
                    QueueConfig.SECKILL_EXCHANGE,
                    QueueConfig.SECKILL_ORDER_ROUTING_KEY,
                    JSONUtil.toJsonStr(order),
                    message -> {
                        message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                        message.getMessageProperties().setMessageId(String.valueOf(order.getId()));
                        message.getMessageProperties().setContentType("application/json");
                        message.getMessageProperties().setHeader(QueueConfig.HEADER_RETRY_COUNT, 0);
                        return message;
                    },
                    correlationData);

            CorrelationData.Confirm confirm =
                    correlationData.getFuture().get(CONFIRM_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            if (!confirm.isAck()) {
                log.error("broker 拒绝订单消息 orderId={} cause={}", order.getId(), confirm.getReason());
                return false;
            }
            // getReturned() 在 confirm future 完成前就已填充，所以这一次检查同时覆盖了
            // "broker 没持久化" 和 "没有任何队列匹配路由键" 两种情况。
            if (correlationData.getReturned() != null) {
                log.error("订单消息无法路由 orderId={} exchange={} rk={}",
                        order.getId(),
                        correlationData.getReturned().getExchange(),
                        correlationData.getReturned().getRoutingKey());
                return false;
            }
            return true;
        } catch (Exception e) {
            log.error("发送秒杀订单消息失败 orderId={}", order.getId(), e);
            return false;
        }
    }

    /**
     * 发送失败时把 Redis 侧的扣减回滚掉。
     *
     * <p>回滚脚本会比对 orderId 才动手，避免把一条其实已经投递成功的有效订单抹掉。
     * 万一回滚本身也失败（比如 Redis 挂了），对账任务会兜底 —— 这正是"对账"存在的意义。
     */
    private void rollbackSeckill(Long voucherId, Long userId, long orderId) {
        try {
            stringRedisTemplate.execute(
                    ROLLBACK_SCRIPT,
                    List.of(SeckillKeys.stock(voucherId), SeckillKeys.orders(voucherId)),
                    userId.toString(), String.valueOf(orderId));
            log.warn("已回滚秒杀资格 voucherId={} userId={} orderId={}", voucherId, userId, orderId);
        } catch (Exception e) {
            log.error("回滚秒杀资格失败，等待对账兜底 voucherId={} userId={} orderId={}",
                    voucherId, userId, orderId, e);
        }
    }

    /**
     * 幂等落库。MQ 消费者与对账任务共用这一个入口。
     *
     * <p><b>顺序是刻意的：先插订单，再扣库存。</b>
     * <ul>
     *   <li>重复消息：INSERT 先撞唯一索引 {@code uk_user_voucher} 抛
     *       {@code DuplicateKeyException}，此时库存分毫未动，事务回滚干净利落。</li>
     *   <li>库存耗尽：抛 {@link SeckillStockExhaustedException}，事务回滚会把刚插入的订单
     *       一并撤销，不会留下"有订单没扣库存"的脏数据。</li>
     * </ul>
     * 两个操作在同一事务里，这是"要么全成、要么全不成"的唯一保证。
     *
     * <p><b>注意异常必须在事务方法之外捕获</b>（见 {@code SeckillVoucherListener}）——
     * 在这里面 catch 住的话事务不会回滚。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        save(voucherOrder);

        boolean deducted = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)
                .update();

        if (!deducted) {
            throw new SeckillStockExhaustedException(voucherOrder.getVoucherId(), voucherOrder.getUserId());
        }
    }
}
