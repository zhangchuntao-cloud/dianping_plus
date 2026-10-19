package com.hmdp.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 秒杀链路的指标集中管理。
 *
 * <p><b>为什么要有这个类</b>：这套可靠性机制（幂等拦截、消息重试、对账补偿）在代码里做对了，
 * 但线上完全看不见——"有没有超卖""消息有没有丢""重试了多少次"都只能靠翻日志。
 * 指标是把这些变成可观测曲线的唯一途径。
 *
 * <p><b>为什么集中在一个类里</b>：和 {@code SeckillKeys} 同样的理由——
 * 指标名散落在各处极易拼错，而拼错的指标不会报错，只会静默地不显示。
 * 集中定义保证"埋点"和"大盘查询"用的是同一份事实源。
 *
 * <p>指标命名遵循 Prometheus 惯例：{@code 领域.对象.动作}，
 * 例如 {@code seckill.requests}、{@code seckill.messages.sent}。
 * Micrometer 会自动把点转成下划线并加 {@code _total} 后缀。
 */
@Component
public class SeckillMetrics {

    /** 秒杀请求的终态。用标签区分而不是建多个计数器，这样 PromQL 里一次查询就能算出各占比 */
    public static final String RESULT_SUCCESS = "success";
    public static final String RESULT_OUT_OF_STOCK = "out_of_stock";
    public static final String RESULT_DUPLICATE = "duplicate";
    public static final String RESULT_NOT_INITIALIZED = "not_initialized";
    public static final String RESULT_SYSTEM_ERROR = "system_error";

    private final MeterRegistry registry;

    /** 请求结果计数，按 result 标签区分 */
    private final Map<String, Counter> requestCounters = new ConcurrentHashMap<>();

    private final Counter messagesSent;
    private final Counter messagesSendFailed;

    private final Counter ordersCreated;
    private final Counter consumeDuplicates;
    private final Counter consumeRetries;
    private final Counter consumeToDlq;

    private final Timer luaTimer;
    private final Timer consumeTimer;

    // ---- 由对账任务周期性刷新的瞬时值 ----
    //
    // 注意：这些是**每个券一份**的状态，但 Gauge 注册时不能带动态的券 id——
    // Micrometer 的 Gauge 一旦注册就固定绑定一个对象。所以这里用「每个券 id
    // 各注册一个 Gauge」的方式：首次为该券刷新时创建，之后复用。
    // 原实现是全局单个 AtomicLong，多券会互相覆盖（压测时同时有券 10 和券 11，
    // Redis/DB 库存曲线就会来回跳）。
    private final Map<Long, VoucherSnapshot> snapshots = new ConcurrentHashMap<>();
    private final AtomicLong reconcileAlertCount = new AtomicLong(0);

    /** 单个券的瞬时状态 */
    private static final class VoucherSnapshot {
        final AtomicLong redisStock = new AtomicLong(-1);
        final AtomicLong dbStock = new AtomicLong(-1);
        final AtomicLong orderCount = new AtomicLong(-1);
        final AtomicLong dlqDepth = new AtomicLong(-1);
    }

    public SeckillMetrics(MeterRegistry registry) {
        this.registry = registry;

        this.messagesSent = Counter.builder("seckill.messages.sent")
                .description("成功投递到 broker 的秒杀订单消息数")
                .register(registry);
        this.messagesSendFailed = Counter.builder("seckill.messages.send.failed")
                .description("投递失败（broker NACK / 无法路由 / 异常）的消息数，每次失败都会回滚 Redis 扣减")
                .register(registry);

        this.ordersCreated = Counter.builder("seckill.orders.created")
                .description("成功落库的秒杀订单数")
                .register(registry);
        this.consumeDuplicates = Counter.builder("seckill.consume.duplicates")
                .description("被唯一索引拦下的重复消息数。这个值长期 > 0 说明消息在重复投递，需要排查")
                .register(registry);
        this.consumeRetries = Counter.builder("seckill.consume.retries")
                .description("消费失败后进入延迟重试队列的次数")
                .register(registry);
        this.consumeToDlq = Counter.builder("seckill.consume.dlq")
                .description("重试耗尽后进入死信队列的消息数。**大于 0 就应当告警**")
                .register(registry);

        this.luaTimer = Timer.builder("seckill.lua.duration")
                .description("秒杀 Lua 预检耗时（含库存判断、去重、扣减）")
                .register(registry);
        this.consumeTimer = Timer.builder("seckill.consume.duration")
                .description("单条订单消息的消费耗时（含落库与扣库存事务）")
                .register(registry);

        // 瞬时值用 Gauge 暴露，**每个券一份**（带 voucher 标签）。
        // 首次刷新某个券时才注册，注册时把该券的 AtomicLong 绑进去；
        // 之后对账任务更新 AtomicLong 就等于更新指标，无需重新注册。
        Gauge.builder("seckill.reconcile.alerts", reconcileAlertCount, AtomicLong::doubleValue)
                .description("对账任务累计发现的异常数（超卖/漂移/丢失订单等）")
                .register(registry);

        // 预注册所有请求终态的计数器。
        // 否则计数器要等第一次出现该终态才注册，导致大盘在启动初期显示"无数据"，
        // 而且 rate() 在第一个数据点之前算不出来。
        for (String result : new String[]{RESULT_SUCCESS, RESULT_OUT_OF_STOCK, RESULT_DUPLICATE,
                RESULT_NOT_INITIALIZED, RESULT_SYSTEM_ERROR}) {
            requestCounters.put(result, Counter.builder("seckill.requests")
                    .description("秒杀请求数，按终态区分")
                    .tag("result", result)
                    .register(registry));
        }
    }

    // ---------------------------------------------------------------- 请求侧

    /** 记录一次秒杀请求的终态 */
    public void recordRequest(String result) {
        requestCounters.computeIfAbsent(result, r -> Counter.builder("seckill.requests")
                        .description("秒杀请求数，按终态区分")
                        .tag("result", r)
                        .register(registry))
                .increment();
    }

    /** 供测试与诊断：当前已注册的终态计数器名 */
    public java.util.Set<String> registeredResults() {
        return java.util.Set.copyOf(requestCounters.keySet());
    }

    /** 记录 Lua 预检耗时 */
    public void recordLuaDuration(long nanos) {
        luaTimer.record(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    // ---------------------------------------------------------------- 消息侧

    public void recordMessageSent() {
        messagesSent.increment();
    }

    public void recordMessageSendFailed() {
        messagesSendFailed.increment();
    }

    // ---------------------------------------------------------------- 消费侧

    public void recordOrderCreated() {
        ordersCreated.increment();
    }

    /** 幂等拦截：消息被唯一索引挡下，属于"正常但值得关注"的事件 */
    public void recordConsumeDuplicate() {
        consumeDuplicates.increment();
    }

    public void recordConsumeRetry() {
        consumeRetries.increment();
    }

    /** 进入死信队列。这是重试阶梯被击穿的信号，应当触发告警 */
    public void recordConsumeToDlq() {
        consumeToDlq.increment();
    }

    public void recordConsumeDuration(long nanos) {
        consumeTimer.record(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    // ---------------------------------------------------------------- 对账侧

    /**
     * 由对账任务为指定券刷新瞬时值。
     *
     * <p>首次调用某个券时注册对应的 Gauge（带 {@code voucher} 标签），之后复用。
     * 死信队列深度是全局的，但仍然挂在每个券的快照上 —— 这样大盘上一个查询
     * 就能把"某个券的库存"和"它的死信积压"放在一起看。
     */
    public void refreshSnapshot(long voucherId, long redisStockValue, long dbStockValue,
                                long orders, long dlq) {
        VoucherSnapshot snapshot = snapshots.computeIfAbsent(voucherId, id -> {
            VoucherSnapshot created = new VoucherSnapshot();
            Gauge.builder("seckill.stock.redis", created.redisStock, AtomicLong::doubleValue)
                    .description("Redis 中的剩余库存")
                    .tag("voucher", String.valueOf(id))
                    .register(registry);
            Gauge.builder("seckill.stock.db", created.dbStock, AtomicLong::doubleValue)
                    .description("MySQL 中的剩余库存。与 redis 那条曲线应当重合，分叉即意味着数据漂移")
                    .tag("voucher", String.valueOf(id))
                    .register(registry);
            Gauge.builder("seckill.orders.count", created.orderCount, AtomicLong::doubleValue)
                    .description("已落库的订单数")
                    .tag("voucher", String.valueOf(id))
                    .register(registry);
            Gauge.builder("seckill.dlq.depth", created.dlqDepth, AtomicLong::doubleValue)
                    .description("死信队列积压深度")
                    .tag("voucher", String.valueOf(id))
                    .register(registry);
            return created;
        });

        snapshot.redisStock.set(redisStockValue);
        snapshot.dbStock.set(dbStockValue);
        snapshot.orderCount.set(orders);
        snapshot.dlqDepth.set(dlq);
    }

    /** 对账累计发现异常时调用 */
    public void recordReconcileAlert() {
        reconcileAlertCount.incrementAndGet();
    }
}
