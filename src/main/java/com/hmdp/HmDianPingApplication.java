package com.hmdp;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 启动类。
 *
 * <p>原先这里挂着 {@code @EnableAspectJAutoProxy(exposeProxy = true)}，那是为了配合
 * 已被删除的 {@code AopContext.currentProxy()} 写法。现在 {@code createVoucherOrder}
 * 由消费者和对账任务通过注入的代理调用，不再需要暴露代理。
 */
@MapperScan("com.hmdp.mapper")
@EnableRabbit
@EnableScheduling
@SpringBootApplication
public class HmDianPingApplication {

    public static void main(String[] args) {
        SpringApplication.run(HmDianPingApplication.class, args);
    }
}
