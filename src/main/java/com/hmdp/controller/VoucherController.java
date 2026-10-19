package com.hmdp.controller;


import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Voucher;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@RestController
@RequestMapping("/voucher")
public class VoucherController {

    /**
     * 允许造券的用户 id 白名单（逗号分隔）。
     *
     * <p>为什么这样做而不是在拦截器里按路径排除：{@code excludePathPatterns} 只匹配路径、
     * 不看 HTTP 方法，而 {@code /voucher/**} 下既有前台要用的只读查询
     * （{@code GET /voucher/list/{shopId}}），也有管理端的造券写操作。
     * 全放行等于任何人都能造券并写 Redis 库存 key——因为造券会把库存写进 Redis，
     * 这等于可以凭空创造秒杀库存。
     *
     * <p>生产环境应该接真正的 RBAC 权限体系；这里用白名单是为了在保留教学项目结构的前提下
     * 堵住这个洞。留空表示不限制（兼容本地开发），**部署时必须配置**。
     */
    @Value("${hmdp.voucher.admin-ids:}")
    private String adminIdsConfig;

    @Resource
    private IVoucherService voucherService;

    /**
     * 新增普通券
     * @param voucher 优惠券信息
     * @return 优惠券id
     */
    @PostMapping
    public Result addVoucher(@RequestBody Voucher voucher) {
        Result denied = requireAdmin();
        if (denied != null) {
            return denied;
        }
        voucherService.addSeckillVoucher(voucher);
        return Result.ok(voucher.getId());
    }

    /**
     * 新增秒杀券
     * @param voucher 优惠券信息，包含秒杀信息
     * @return 优惠券id
     */
    @PostMapping("seckill")
    public Result addSeckillVoucher(@RequestBody Voucher voucher) {
        Result denied = requireAdmin();
        if (denied != null) {
            return denied;
        }
        voucherService.addSeckillVoucher(voucher);
        return Result.ok(voucher.getId());
    }

    /**
     * 查询店铺的优惠券列表（只读，前台使用，不需要鉴权）
     * @param shopId 店铺id
     * @return 优惠券列表
     */
    @GetMapping("/list/{shopId}")
    public Result queryVoucherOfShop(@PathVariable("shopId") Long shopId) {
       return voucherService.queryVoucherOfShop(shopId);
    }

    /**
     * 造券鉴权。
     *
     * @return null 表示放行；非 null 表示应当直接返回给调用方
     */
    private Result requireAdmin() {
        // 未配置白名单时不做限制 —— 保持本地开发的便利性。
        // 部署时必须通过 hmdp.voucher.admin-ids 显式配置。
        if (StrUtil.isBlank(adminIdsConfig)) {
            log.warn("未配置 hmdp.voucher.admin-ids，造券接口当前对所有人开放");
            return null;
        }

        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        for (String id : adminIdsConfig.split(",")) {
            if (id.trim().equals(user.getId().toString())) {
                return null;
            }
        }
        log.warn("非管理员尝试造券 userId={}", user.getId());
        return Result.fail("无权限");
    }
}
