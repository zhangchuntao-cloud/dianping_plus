-- 秒杀资格预检（原子）
--
-- KEYS[1] = seckill:stock:{voucherId}   剩余库存（String）
-- KEYS[2] = seckill:order:{voucherId}   下单记录（Hash，userId -> orderId）
-- ARGV[1] = userId
-- ARGV[2] = orderId
--
-- 返回：
--   -1  库存 key 不存在（未初始化 / 被清空，需触发重建）
--    1  库存不足
--    2  重复下单
--    0  抢购成功，可以发消息
--
-- 与旧版的区别（每一条都是修 bug）：
--   1. key 通过 KEYS[] 传入而非在脚本里字符串拼接 —— 这是 Redis Cluster 的硬要求，
--      代理需要靠 KEYS 声明来判断 slot。旧版传 Collections.emptyList() 上集群必挂。
--   2. 删掉了尾部的 xadd stream.orders —— 那是被废弃的 Redis Stream 方案残留，
--      没有任何消费者，每次成功下单都往一个永不读取、永不裁剪的 stream 里追加，
--      是无界内存泄漏。
--   3. 补上了显式 return 0 —— 旧脚本没有 return，执行完自然结束返回 nil，
--      Java 侧 result == null 被当成 r = 0 即"成功"。结果是脚本失败与成功无法区分，
--      连脚本根本没跑通都会去发 MQ 消息。
--   4. SET/SISMEMBER/SADD 换成 HASH/HEXISTS/HSET，value 存 orderId，供对账修复使用。

local stock = redis.call('GET', KEYS[1])
if not stock then
    return -1
end

if tonumber(stock) <= 0 then
    return 1
end

if redis.call('HEXISTS', KEYS[2], ARGV[1]) == 1 then
    return 2
end

redis.call('DECR', KEYS[1])
redis.call('HSET', KEYS[2], ARGV[1], ARGV[2])

return 0
