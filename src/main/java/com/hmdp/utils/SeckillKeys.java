package com.hmdp.utils;

/**
 * 秒杀相关的 Redis key 统一构造入口。
 *
 * <p>为什么要有这个类：原来 key 是在 {@code seckill.lua} 里用字符串拼接硬编码的，
 * Java 侧另有一份 {@link RedisConstants#SECKILL_STOCK_KEY} 前缀常量，两处各写各的，
 * 改一处必漏另一处。这里收敛成唯一事实源。
 *
 * <p>为什么带 {@code {}}：Redis Cluster 按 CRC16(key) 决定 slot，Lua 脚本里访问的多个 key
 * 必须落在同一 slot，否则直接报 CROSSSLOT 错误。用 {@code {voucherId}} 做 hash tag，
 * 使 {@code seckill:stock:{10}} 与 {@code seckill:order:{10}} 的 CRC16 都只对 {@code 10} 计算，
 * 从而保证同券的所有 key 同 slot。
 */
public final class SeckillKeys {

    private SeckillKeys() {
    }

    /** 秒杀库存（String，剩余可售数量） */
    public static String stock(long voucherId) {
        return "seckill:stock:" + tag(voucherId);
    }

    /**
     * 秒杀下单记录（Hash，userId -> orderId）。
     *
     * <p>用 Hash 而非 Set 是刻意为之：Hash 的 value 存了 orderId，对账任务才能据此
     * 把"Redis 有记录但 DB 缺订单"的丢失消息补回来。Set 只能存 userId，
     * 对账就退化成"只能发现、无法修复"。
     */
    public static String orders(long voucherId) {
        return "seckill:order:" + tag(voucherId);
    }

    /** 秒杀券总库存（String），用于对账时计算"应剩库存 = 总量 - 已售" */
    public static String total(long voucherId) {
        return "seckill:total:" + tag(voucherId);
    }

    private static String tag(long voucherId) {
        return "{" + voucherId + "}";
    }
}
