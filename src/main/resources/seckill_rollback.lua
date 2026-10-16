-- 秒杀资格回滚（消息发送失败时的补偿）
--
-- KEYS[1] = seckill:stock:{voucherId}
-- KEYS[2] = seckill:order:{voucherId}
-- ARGV[1] = userId
-- ARGV[2] = orderId
--
-- 返回：1 已回滚 | 0 无需回滚
--
-- 场景：Lua 预检已经扣了 Redis 库存并写入下单记录，但随后往 RabbitMQ 发消息失败
-- （broker NACK / 超时 / 路由不到队列）。若不补偿，这个用户会永久卡在"已下单"状态
-- ——库存白扣、且他自己再也抢不到（Lua 返回 2），这就是旧版最隐蔽的资损点。
--
-- 为什么必须比对 ARGV[2]：不能无脑删。设想一个延迟到达的重复请求，
-- 它携带的 orderId 与当前记录不符，说明它对应的那条消息其实已经投递成功了，
-- 此时若直接 HDEL + INCR，会把一笔**有效订单**的记录抹掉并把库存加回去 ——
-- 自己制造超卖。只有 orderId 精确匹配才说明"这条记录确实是本次失败操作写入的"。

if redis.call('HGET', KEYS[2], ARGV[1]) == ARGV[2] then
    redis.call('HDEL', KEYS[2], ARGV[1])
    redis.call('INCR', KEYS[1])
    return 1
end

return 0
