package com.hmdp.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 秒杀消息链路拓扑。
 *
 * <pre>
 *  seckill.exchange ──seckill.order──▶ seckill.order.queue   （业务队列，手动 ACK，无 TTL）
 *                                              │
 *                          消费失败且未超重试上限 │ 显式 republish
 *                                              ▼
 *  seckill.retry.exchange ──seckill.retry──▶ seckill.retry.queue （纯延迟队列，TTL 5s，无人消费）
 *                                              │
 *                              TTL 到期后 DLX 回业务交换机，重新走一遍业务队列
 *
 *  重试超上限 ──▶ seckill.dlx.exchange ──seckill.dead──▶ seckill.dlq （停车场，无消费者，人工介入）
 * </pre>
 *
 * <p><b>与旧拓扑的关键差别</b>：旧版把 {@code x-message-ttl} 设在<b>业务队列</b>上，
 * 语义完全反了 —— {@code x-message-ttl} 是「消息在 ready 集合里驻留超时即变死信」，
 * 与消费成功与否无关。它导致两件事：一是业务队列积压超过 TTL 时消息被静默改道到
 * 死信处理器；二是死信处理器 {@code receivedD} 里连死信配置都没有，进去就出不来。
 * 正确做法是：<b>业务队列不设任何 TTL 和 DLX</b>，由消费者显式决定「重试还是进停车场」，
 * TTL 只出现在专门的延迟重试队列上。
 */
@Configuration
public class QueueConfig {

    // ---------- 业务 ----------
    public static final String SECKILL_EXCHANGE = "seckill.exchange";
    public static final String SECKILL_ORDER_QUEUE = "seckill.order.queue";
    public static final String SECKILL_ORDER_ROUTING_KEY = "seckill.order";

    // ---------- 延迟重试 ----------
    public static final String SECKILL_RETRY_EXCHANGE = "seckill.retry.exchange";
    public static final String SECKILL_RETRY_QUEUE = "seckill.retry.queue";
    public static final String SECKILL_RETRY_ROUTING_KEY = "seckill.retry";

    // ---------- 最终死信 ----------
    public static final String SECKILL_DLX_EXCHANGE = "seckill.dlx.exchange";
    public static final String SECKILL_DLQ = "seckill.dlq";
    public static final String SECKILL_DLQ_ROUTING_KEY = "seckill.dead";

    /** 消息头：已重试次数 */
    public static final String HEADER_RETRY_COUNT = "x-retry-count";
    /** 消息头：最后一次失败原因（异常类名），便于死信队列分诊 */
    public static final String HEADER_FAIL_REASON = "x-fail-reason";

    /** 超过该次数进死信队列 */
    public static final int MAX_RETRY = 3;
    /** 重试延迟（毫秒） */
    public static final int RETRY_TTL_MS = 5_000;

    // ==================== 声明入口 ====================

    /**
     * 显式声明 {@link RabbitAdmin}：它负责在启动时把上面这些 exchange/queue/binding 真正建到 broker 上，
     * 对账任务也用它读取队列深度。Boot 3.2 不再自动配置这个 bean，需要自己给。
     */
    @Bean
    public RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        return new RabbitAdmin(connectionFactory);
    }

    // ==================== 业务 ====================

    @Bean
    public DirectExchange seckillExchange() {
        return ExchangeBuilder.directExchange(SECKILL_EXCHANGE).durable(true).build();
    }

    /**
     * 业务队列。刻意不带任何参数：
     * <ul>
     *   <li>无 {@code x-message-ttl} —— 积压的消息永远不会被时间悄悄改道</li>
     *   <li>无 {@code x-dead-letter-*} —— 失败走向由消费者显式决定，
     *       而不是"因为排队久了所以换个人处理"</li>
     * </ul>
     */
    @Bean
    public Queue seckillOrderQueue() {
        return QueueBuilder.durable(SECKILL_ORDER_QUEUE).build();
    }

    @Bean
    public Binding seckillOrderBinding(Queue seckillOrderQueue, DirectExchange seckillExchange) {
        return BindingBuilder.bind(seckillOrderQueue).to(seckillExchange).with(SECKILL_ORDER_ROUTING_KEY);
    }

    // ==================== 延迟重试 ====================

    @Bean
    public DirectExchange seckillRetryExchange() {
        return ExchangeBuilder.directExchange(SECKILL_RETRY_EXCHANGE).durable(true).build();
    }

    /**
     * 纯延迟队列：没有任何消费者。消息在此静置 {@link #RETRY_TTL_MS}，
     * 到期后由 DLX 送回业务交换机，从而以完全相同的路径重新投递一次。
     *
     * <p>重试次数不依赖 broker 的 {@code x-death} 头（其语义随版本与死信原因变化，
     * 不好可靠累加），而是由消费者在 republish 时显式写 {@link #HEADER_RETRY_COUNT}。
     */
    @Bean
    public Queue seckillRetryQueue() {
        return QueueBuilder.durable(SECKILL_RETRY_QUEUE)
                .ttl(RETRY_TTL_MS)
                .deadLetterExchange(SECKILL_EXCHANGE)
                .deadLetterRoutingKey(SECKILL_ORDER_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding seckillRetryBinding(Queue seckillRetryQueue, DirectExchange seckillRetryExchange) {
        return BindingBuilder.bind(seckillRetryQueue).to(seckillRetryExchange).with(SECKILL_RETRY_ROUTING_KEY);
    }

    // ==================== 最终死信 ====================

    @Bean
    public DirectExchange seckillDlxExchange() {
        return ExchangeBuilder.directExchange(SECKILL_DLX_EXCHANGE).durable(true).build();
    }

    /**
     * 停车场。<b>故意不注册 {@code @RabbitListener}</b> —— 消息停在这里等人工介入，
     * 而不是被某个"兜底处理器"再自动跑一遍（旧版 {@code QD} 就是这么干的，
     * 结果是绕过所有保证又执行一次）。
     *
     * <p>对账任务会监控本队列深度，大于 0 即告警。
     */
    @Bean
    public Queue seckillDlq() {
        return QueueBuilder.durable(SECKILL_DLQ).build();
    }

    @Bean
    public Binding seckillDlqBinding(Queue seckillDlq, DirectExchange seckillDlxExchange) {
        return BindingBuilder.bind(seckillDlq).to(seckillDlxExchange).with(SECKILL_DLQ_ROUTING_KEY);
    }
}
