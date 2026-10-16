package com.hmdp.listener;

import cn.hutool.json.JSONUtil;
import com.hmdp.config.QueueConfig;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 秒杀订单消费者。
 *
 * <p>三条铁律：
 * <ol>
 *   <li><b>事务提交之后才 ACK</b>。业务方法返回即事务已提交，此时 ACK 才安全。
 *       若在事务内 ACK，事务随后回滚就会出现"消息已确认但活没干" —— 静默丢单。</li>
 *   <li><b>永远不用 {@code basicNack(requeue=true)}</b>。它会让消息立刻重新投递，
 *       形成没有延迟、没有上限的热循环 —— 这正是旧版 AUTO ack +
 *       {@code defaultRequeueRejected=true} 的行为，一个毒消息就能把 CPU 和日志打满。
 *       重试一律走延迟队列。</li>
 *   <li><b>原消息无论如何都 ACK</b>。重试靠 republish 一份新消息，
 *       而不是把原消息留在队列里反复投递。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillVoucherListener {

    private final IVoucherOrderService voucherOrderService;
    private final RabbitTemplate rabbitTemplate;

    @RabbitListener(queues = QueueConfig.SECKILL_ORDER_QUEUE)
    public void onSeckillOrder(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();

        VoucherOrder order;
        try {
            order = JSONUtil.toBean(new String(message.getBody(), StandardCharsets.UTF_8), VoucherOrder.class);
        } catch (Exception e) {
            log.error("秒杀消息无法解析，转入死信队列", e);
            republish(message, QueueConfig.SECKILL_DLX_EXCHANGE, QueueConfig.SECKILL_DLQ_ROUTING_KEY,
                    retryCount(message), "parse-error");
            channel.basicAck(deliveryTag, false);
            return;
        }

        try {
            voucherOrderService.createVoucherOrder(order);
            channel.basicAck(deliveryTag, false);
        } catch (DuplicateKeyException e) {
            // 唯一索引 uk_user_voucher 挡住了重复投递 —— 这是幂等成功，不是失败。
            // 绝不能重试，否则会无限循环。
            log.info("重复订单消息，按幂等成功处理 orderId={} userId={}",
                    order.getId(), order.getUserId());
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            int retry = retryCount(message);
            if (retry < QueueConfig.MAX_RETRY) {
                log.warn("秒杀订单处理失败，第 {}/{} 次重试 orderId={}",
                        retry + 1, QueueConfig.MAX_RETRY, order.getId(), e);
                republish(message, QueueConfig.SECKILL_RETRY_EXCHANGE, QueueConfig.SECKILL_RETRY_ROUTING_KEY,
                        retry + 1, e.getClass().getSimpleName());
            } else {
                log.error("秒杀订单重试耗尽，转入死信队列 orderId={}", order.getId(), e);
                republish(message, QueueConfig.SECKILL_DLX_EXCHANGE, QueueConfig.SECKILL_DLQ_ROUTING_KEY,
                        retry, e.getClass().getSimpleName());
            }
            channel.basicAck(deliveryTag, false);
        }
    }

    /**
     * 重新投递一份消息（重试队列或死信队列）。
     *
     * <p>失败时只记日志不抛异常：此时原消息仍会被 ACK，看起来像是丢了消息，
     * 但 Redis 里的下单记录还在，对账任务会把这条"超时未落库"的订单重新驱动一遍。
     * 这是"至少一次投递 + 对账兜底"的组合，而不是指望单点万无一失。
     */
    private void republish(Message original, String exchange, String routingKey, int retry, String reason) {
        CorrelationData correlationData = new CorrelationData();
        try {
            rabbitTemplate.convertAndSend(exchange, routingKey, original.getBody(), message -> {
                message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                message.getMessageProperties().setContentType(original.getMessageProperties().getContentType());
                message.getMessageProperties().setMessageId(original.getMessageProperties().getMessageId());
                message.getMessageProperties().setHeader(QueueConfig.HEADER_RETRY_COUNT, retry);
                message.getMessageProperties().setHeader(QueueConfig.HEADER_FAIL_REASON, reason);
                return message;
            }, correlationData);
            correlationData.getFuture().get(500, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.error("重新投递到 {} 失败，将由对账任务兜底修复", exchange, e);
        }
    }

    private int retryCount(Message message) {
        Object value = message.getMessageProperties().getHeader(QueueConfig.HEADER_RETRY_COUNT);
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
