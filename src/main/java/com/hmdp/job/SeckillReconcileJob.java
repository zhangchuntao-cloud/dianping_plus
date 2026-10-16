package com.hmdp.job;

import com.hmdp.config.QueueConfig;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.exception.SeckillStockExhaustedException;
import com.hmdp.metrics.SeckillMetrics;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.SeckillKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 秒杀对账任务 —— 整个可靠性设计的最后一道防线。
 *
 * <p>前面几层（Redis 预检、publisher confirm、消费幂等、重试队列）都在尽力保证"不出错"，
 * 这个任务负责的是"出错了也能自己发现并修好"。它回答三个问题：
 *
 * <ol>
 *   <li><b>Redis 说有单、DB 没有</b> → 消息真的丢了 → <b>自动补单</b>。
 *       这是唯一需要自动修复的场景，因为它是幂等的：Redis 里存着 orderId，
 *       补一次 INSERT 而已，撞唯一索引就是别人已经补过了。</li>
 *   <li><b>DB 有单、Redis 没有</b> → 只告警，绝不删数据。
 *       删数据是破坏性的，而且大概率说明有人手工动过 Redis。</li>
 *   <li><b>库存对不上 / 超卖</b> → 只告警。强制改库存会掩盖真正的 bug。</li>
 * </ol>
 *
 * <p>自动修复的原则：<b>只做幂等且非破坏性的操作</b>。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillReconcileJob {

    private static final String LOCK_KEY = "lock:seckill:reconcile";
    /** 连续观察到几次库存漂移才回写 Redis，避免在途消息造成抖动 */
    private static final int DRIFT_CONFIRM_TIMES = 2;

    private final StringRedisTemplate stringRedisTemplate;
    private final ISeckillVoucherService seckillVoucherService;
    private final IVoucherOrderService voucherOrderService;
    private final RabbitAdmin rabbitAdmin;
    private final SeckillMetrics seckillMetrics;

    /** voucherId -> 连续漂移次数 */
    private final Map<Long, Integer> driftStreak = new ConcurrentHashMap<>();
    /** 各类告警计数，便于压测后一次性读取 */
    private final Map<String, AtomicLong> alertCounters = new ConcurrentHashMap<>();

    @Scheduled(fixedDelayString = "${hmdp.seckill.reconcile-interval-ms:60000}")
    public void reconcile() {
        // 多实例部署时只让一个节点执行。TTL 小于调度间隔，节点挂掉后能自愈。
        Boolean locked = stringRedisTemplate.opsForValue()
                .setIfAbsent(LOCK_KEY, "1", Duration.ofSeconds(50));
        if (!Boolean.TRUE.equals(locked)) {
            return;
        }
        try {
            for (Long voucherId : candidates()) {
                try {
                    reconcileOne(voucherId);
                } catch (Exception e) {
                    log.error("对账失败 voucherId={}", voucherId, e);
                }
            }
            checkDlq();
        } finally {
            stringRedisTemplate.delete(LOCK_KEY);
        }
    }

    private void reconcileOne(Long voucherId) {
        String totalStr = stringRedisTemplate.opsForValue().get(SeckillKeys.total(voucherId));
        if (totalStr == null) {
            return; // 不是被跟踪的秒杀券
        }
        int total = Integer.parseInt(totalStr);

        Map<Object, Object> redisOrders =
                stringRedisTemplate.opsForHash().entries(SeckillKeys.orders(voucherId));
        List<VoucherOrder> dbOrders = voucherOrderService.lambdaQuery()
                .eq(VoucherOrder::getVoucherId, voucherId)
                .list();
        Map<Long, Long> dbOrderIdByUser = new HashMap<>();
        for (VoucherOrder order : dbOrders) {
            dbOrderIdByUser.put(order.getUserId(), order.getId());
        }

        // ---- 场景二：DB 有单、Redis 无单 → 只告警 ----
        // 这个判断与流水线是否静止无关：Redis 一定先于 DB 写入，
        // 所以"DB 有单但 Redis 没记录"只可能是 Redis 丢过数据，不存在时序误判。
        for (VoucherOrder order : dbOrders) {
            if (!redisOrders.containsKey(order.getUserId().toString())) {
                alert("DB_ORDER_WITHOUT_REDIS_MARKER", voucherId,
                        "userId=" + order.getUserId() + " orderId=" + order.getId());
            }
        }

        // 下面这些判断必须在**流水线静止**时做。
        //
        // 原因：orderCount 和 dbStock 是两条独立的 SQL，各自是一个瞬间的快照。
        // 消费进行中时，第一次读到的订单数和第二次读到的库存来自不同时刻，
        // 二者必然"看起来"对不上 —— 这不是数据不一致，是读的不是同一时刻。
        // 压测时实测到过这种误报（dbStock=0 expected=18，而那一刻消息还在消费中）。
        if (!isQuiescent()) {
            driftStreak.remove(voucherId);
            return;
        }

        // ---- 场景一：Redis 有单、DB 无单 → 补单 ----
        for (Map.Entry<Object, Object> entry : redisOrders.entrySet()) {
            long userId = Long.parseLong(entry.getKey().toString());
            long orderId = Long.parseLong(entry.getValue().toString());
            Long existingOrderId = dbOrderIdByUser.get(userId);

            if (existingOrderId != null) {
                if (existingOrderId != orderId) {
                    // orderId 不一致说明 Redis 记录被覆盖过，人工介入
                    alert("ORDER_ID_MISMATCH", voucherId, "userId=" + userId
                            + " redisOrderId=" + orderId + " dbOrderId=" + existingOrderId);
                }
                continue;
            }

            try {
                voucherOrderService.createVoucherOrder(new VoucherOrder()
                        .setId(orderId)
                        .setUserId(userId)
                        .setVoucherId(voucherId));
                alert("ORDER_REPAIRED", voucherId, "userId=" + userId + " orderId=" + orderId);
                dbOrders.add(new VoucherOrder().setId(orderId).setUserId(userId).setVoucherId(voucherId));
            } catch (DuplicateKeyException raced) {
                // 消费者抢在我们前面落库了，正常
            } catch (SeckillStockExhaustedException exhausted) {
                alert("REPAIR_STOCK_EXHAUSTED", voucherId, "userId=" + userId + " orderId=" + orderId);
            }
        }

        // ---- 场景三：库存一致性 ----
        int orderCount = dbOrders.size();
        int expectedStock = total - orderCount;
        Integer dbStock = seckillVoucherService.getById(voucherId).getStock();

        if (expectedStock < 0) {
            alert("OVERSELL", voucherId, "total=" + total + " orderCount=" + orderCount);
            return;
        }
        if (dbStock == null || dbStock != expectedStock) {
            alert("DB_STOCK_DRIFT", voucherId, "dbStock=" + dbStock + " expected=" + expectedStock);
        }

        // ---- 场景四：Redis 库存回写 ----
        resyncRedisStock(voucherId, dbStock, expectedStock);

        // 把这一轮的观测值刷进指标，供 Grafana 展示。
        // 注意：这些值只在**流水线静止**时才是可比的（见上方 isQuiescent 的注释），
        // 所以非静止时不去刷新——否则大盘上会出现假的"库存漂移"尖刺。
        String redisStockStr = stringRedisTemplate.opsForValue().get(SeckillKeys.stock(voucherId));
        long redisStockValue = redisStockStr == null ? -1 : Long.parseLong(redisStockStr);
        seckillMetrics.refreshSnapshot(
                redisStockValue,
                dbStock == null ? -1 : dbStock,
                orderCount,
                queueDepth(QueueConfig.SECKILL_DLQ));
    }

    /** 业务队列与重试队列都已排空（含未 ACK 的在途消息） */
    private boolean isQuiescent() {
        return queueDepth(QueueConfig.SECKILL_ORDER_QUEUE) == 0
                && queueDepth(QueueConfig.SECKILL_RETRY_QUEUE) == 0;
    }

    /**
     * 把 Redis 库存回写成 DB 库存。
     *
     * <p>调用前已确保流水线静止（见 {@link #reconcileOne}）。即便如此仍要求
     * <b>连续 {@link #DRIFT_CONFIRM_TIMES} 次观察到同一个漂移值</b>才动手 ——
     * 库存是最敏感的状态，宁可多等一个周期也不要被瞬时抖动带偏。
     */
    private void resyncRedisStock(Long voucherId, Integer dbStock, int expectedStock) {
        if (dbStock == null || expectedStock < 0) {
            return;
        }

        String redisStockStr = stringRedisTemplate.opsForValue().get(SeckillKeys.stock(voucherId));
        int redisStock = redisStockStr == null ? Integer.MIN_VALUE : Integer.parseInt(redisStockStr);
        if (redisStock == dbStock) {
            driftStreak.remove(voucherId);
            return;
        }

        int streak = driftStreak.merge(voucherId, 1, Integer::sum);
        if (streak >= DRIFT_CONFIRM_TIMES) {
            stringRedisTemplate.opsForValue()
                    .set(SeckillKeys.stock(voucherId), String.valueOf(dbStock));
            alert("REDIS_STOCK_RESYNCED", voucherId,
                    "from=" + redisStock + " to=" + dbStock + " afterObservations=" + streak);
            driftStreak.remove(voucherId);
        }
    }

    /** 死信队列非空即告警 —— 这是重试阶梯被击穿的唯一信号 */
    private void checkDlq() {
        long depth = queueDepth(QueueConfig.SECKILL_DLQ);
        if (depth > 0) {
            alert("DLQ_NOT_EMPTY", null, "depth=" + depth);
        }
    }

    private List<Long> candidates() {
        // tb_seckill_voucher 是小表，直接扫。规模变大后可换成 SCAN seckill:total:*
        LocalDateTime since = LocalDateTime.now().minusDays(1);
        return seckillVoucherService.lambdaQuery()
                .gt(SeckillVoucher::getEndTime, since)
                .list()
                .stream()
                .map(SeckillVoucher::getVoucherId)
                .toList();
    }

    private long queueDepth(String queueName) {
        try {
            var info = rabbitAdmin.getQueueInfo(queueName);
            return info == null ? 0 : info.getMessageCount();
        } catch (Exception e) {
            log.warn("读取队列深度失败 queue={}", queueName, e);
            return -1;
        }
    }

    /** 结构化告警：统一 tag 便于日志检索，并累计计数供压测断言读取 */
    private void alert(String type, Long voucherId, String detail) {
        alertCounters.computeIfAbsent(type, k -> new AtomicLong()).incrementAndGet();
        // 同步打进指标，这样 Grafana 上能看到"对账发现了多少异常"的趋势，
        // 而不是只能翻日志
        seckillMetrics.recordReconcileAlert();
        log.error("SEC_ALERT {} voucherId={} {}", type, voucherId, detail);
    }

    /** 供测试读取告警计数 */
    public Map<String, Long> alertSnapshot() {
        Map<String, Long> snapshot = new HashMap<>();
        alertCounters.forEach((k, v) -> snapshot.put(k, v.get()));
        return snapshot;
    }
}
