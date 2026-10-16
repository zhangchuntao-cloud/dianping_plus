package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    /** 秒杀入口：Redis 原子预检 + 投递 MQ，请求线程不碰 DB */
    Result seckillVoucher(Long voucherId);

    /** 幂等落库，由 MQ 消费者与对账任务共同调用 */
    void createVoucherOrder(VoucherOrder voucherOrder);
}
