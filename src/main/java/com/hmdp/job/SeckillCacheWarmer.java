package com.hmdp.job;

import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.SeckillKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 秒杀缓存预热 / 灾难恢复。
 *
 * <p>解决的问题：秒杀的全部前置状态都在 Redis 里（库存、下单记录）。Redis 一旦重启、
 * 被 flush、或 key 因内存淘汰而消失，{@code seckill.lua} 会一直返回 {@code -1}，
 * 用户看到的是"秒杀尚未开始或已结束" —— 而且<b>永远不会自愈</b>，因为没有任何代码
 * 会把状态从 DB 写回 Redis。旧版就是这样：Redis 一丢，这个券的秒杀就永久废了。
 *
 * <p>这里在应用启动时按 DB 重建缺失的 key。<b>只补缺失的，绝不覆盖已存在的</b> ——
 * 覆盖会把正在进行中的秒杀状态冲掉。
 *
 * <p>注意 {@code total}（总库存）在 DB 里没有对应列，但可以反推：
 * {@code 总量 = 当前剩余库存 + 已售订单数}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillCacheWarmer implements ApplicationRunner {

    private final StringRedisTemplate stringRedisTemplate;
    private final ISeckillVoucherService seckillVoucherService;
    private final IVoucherOrderService voucherOrderService;

    @Override
    public void run(ApplicationArguments args) {
        List<SeckillVoucher> vouchers;
        try {
            vouchers = seckillVoucherService.lambdaQuery()
                    .gt(SeckillVoucher::getEndTime, LocalDateTime.now())
                    .list();
        } catch (Exception e) {
            log.error("秒杀缓存预热失败（不影响启动），将由对账任务在运行期修复", e);
            return;
        }

        int repaired = 0;
        for (SeckillVoucher voucher : vouchers) {
            try {
                repaired += warmUp(voucher);
            } catch (Exception e) {
                log.error("预热失败 voucherId={}", voucher.getVoucherId(), e);
            }
        }
        log.info("秒杀缓存预热完成：扫描 {} 张进行中的券，补建 {} 个 key", vouchers.size(), repaired);
    }

    private int warmUp(SeckillVoucher voucher) {
        Long voucherId = voucher.getVoucherId();
        int repaired = 0;

        String orderKey = SeckillKeys.orders(voucherId);
        List<VoucherOrder> orders = voucherOrderService.lambdaQuery()
                .eq(VoucherOrder::getVoucherId, voucherId)
                .list();

        // 下单记录：Hash userId -> orderId
        if (Boolean.FALSE.equals(stringRedisTemplate.hasKey(orderKey)) && !orders.isEmpty()) {
            Map<String, String> entries = new java.util.HashMap<>();
            for (VoucherOrder order : orders) {
                entries.put(order.getUserId().toString(), order.getId().toString());
            }
            stringRedisTemplate.opsForHash().putAll(orderKey, entries);
            repaired++;
            log.warn("重建下单记录 voucherId={} 共 {} 条", voucherId, entries.size());
        }

        // 总库存：DB 没有这一列，用 当前剩余 + 已售 反推
        String totalKey = SeckillKeys.total(voucherId);
        if (Boolean.FALSE.equals(stringRedisTemplate.hasKey(totalKey))) {
            int total = voucher.getStock() + orders.size();
            stringRedisTemplate.opsForValue().set(totalKey, String.valueOf(total));
            repaired++;
            log.warn("重建总库存 voucherId={} total={}（剩余 {} + 已售 {}）",
                    voucherId, total, voucher.getStock(), orders.size());
        }

        // 剩余库存
        String stockKey = SeckillKeys.stock(voucherId);
        if (Boolean.FALSE.equals(stringRedisTemplate.hasKey(stockKey))) {
            stringRedisTemplate.opsForValue().set(stockKey, String.valueOf(voucher.getStock()));
            repaired++;
            log.warn("重建剩余库存 voucherId={} stock={}", voucherId, voucher.getStock());
        }

        return repaired;
    }
}
