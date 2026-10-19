package com.hmdp.exception;

/**
 * Redis 预检说有库存，但 MySQL 扣减时发现库存已空 —— 说明两边已经漂移。
 *
 * <p>抛出它会回滚整个事务（含已插入的订单行），消息按普通异常走重试；
 * 重试耗尽后进死信队列，由对账任务告警。单独建一个类型是为了让死信里的
 * {@code x-fail-reason} 能一眼看出"这是库存漂移，不是代码 bug"。
 */
public class SeckillStockExhaustedException extends RuntimeException {

    public SeckillStockExhaustedException(Long voucherId, Long userId) {
        super("秒杀库存不一致：Redis 预检通过但 DB 扣减失败, voucherId=" + voucherId + ", userId=" + userId);
    }
}
