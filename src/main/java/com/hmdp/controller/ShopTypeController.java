package com.hmdp.controller;


import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.service.IShopTypeService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/shop-type")
public class ShopTypeController {
    @Resource
    private IShopTypeService typeService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @GetMapping("list")
    public Result queryTypeList() {
        // 1. 从Redis查询缓存
        String cacheKey = "cache:shop:list";
        String cachedTypeList = stringRedisTemplate.opsForValue().get(cacheKey);
        if (cachedTypeList != null) {
            // 2. 如果缓存存在，直接返回
            return Result.ok(JSONUtil.toList(cachedTypeList, ShopType.class));
        }

        // 3. 如果缓存不存在，查询数据库
        List<ShopType> typeList = typeService
                .query().orderByAsc("sort").list();
        if (typeList == null) {
            // 数据库中不存在该数据，返回失败信息
            return Result.fail("店铺类型不存在");
        }
        // 4. 将查询结果写入Redis缓存
        stringRedisTemplate.opsForValue().set(cacheKey, JSONUtil.toJsonStr(typeList));
        return Result.ok(typeList);
    }
}
