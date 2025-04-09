package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);//开启10个线程


    @Override
    public Result queryById(Long id) {
        //缓存穿透
        //Shop shop = queryWithPassThrough(id);

        //互斥锁解决缓存击穿
        //Shop shop = queryWithMutex(id);
        // 逻辑过期解决缓存击穿
        Shop shop = queryWithLogicalExpire(id);
        if(shop==null) return Result.fail("店铺不存在!");
        return Result.ok(shop);
    }

    public Shop queryWithLogicalExpire(Long id) {
        // 1. 从redis中查询缓存
        String cacheKey = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(cacheKey);
        // 2. 判断是否存在
        if (StrUtil.isBlank(shopJson)) {
            // 3. 不存在，直接返回
            return null;
        }

        //3.命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);

        //4.判断是否过期
        LocalDateTime expireTime = redisData.getExpireTime();
        if(expireTime.isAfter(LocalDateTime.now())){
            //未过期直接返回
            return shop;
        }
        // 5. 缓存过期 需要重建缓存
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        boolean hasLock = tryLock(lockKey);
        // 获取互斥锁失败，其他线程正在重建，返回过期的数据先
        if (!hasLock) {
            return shop;
        }
        // 获取互斥锁成功，开启线程重建缓存
        // Double Check 再次检查缓存是否存在
        shopJson = stringRedisTemplate.opsForValue().get(cacheKey);
        if (StrUtil.isNotBlank(shopJson)) {
            RedisData doubleCheckRedisData = JSONUtil.toBean(shopJson, RedisData.class);
            LocalDateTime doubleCheckExpireTime = doubleCheckRedisData.getExpireTime();
            if (doubleCheckExpireTime.isAfter(LocalDateTime.now())) {
                // 缓存已重建且未过期，直接返回
                return JSONUtil.toBean((JSONObject)doubleCheckRedisData.getData(), Shop.class);
            }
        }
        CACHE_REBUILD_EXECUTOR.submit(() -> {
            try {
                this.saveShop2Cache(id,20L);
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                unLock(lockKey);
            }
        });
        return shop;

    }

    public Shop queryWithPassThrough(Long id) {
        // 1. 从redis中查询缓存
        String cacheKey = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(cacheKey);
        // 2. 判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 3. 存在，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 如果上面的判断不对，那么就是我们设置的""(有缓存"",证明数据库内肯定是没有的)或者null(没有缓存)
        if (shopJson != null) {
            return null;
        }
        // 4. 不存在，根据id查询数据库
        Shop shop = getById(id);
        // 5. 数据库中也不存在，返回错误
        if (shop == null) {
            // 数据库中不存在，将空值写入redis
            stringRedisTemplate.opsForValue().set(cacheKey, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 数据库中存在，重建缓存，并返回店铺数据
        stringRedisTemplate.opsForValue().set(cacheKey, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }

    public Shop queryWithMutex(Long id) {
        // 1. 从redis中查询缓存
        String cacheKey = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(cacheKey);
        // 2. 判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 3. 存在，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 如果上面的判断不对，那么就是我们设置的""(有缓存"",证明数据库内肯定是没有的)或者null(没有缓存)
        if (shopJson != null) {
            return null;
        }
        // 4. 开始实现缓存重建
        // 4.1 获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean hasLock = tryLock(lockKey);
            // 4.2 判断是否获取成功
            if (!hasLock) {
                // 4.3 失败，则休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            // 4.4 加锁成功，根据id查询数据库
            shop = getById(id);
            // 5. 数据库中也不存在，返回错误
            if (shop == null) {
                // 数据库中不存在，将空值写入redis
                stringRedisTemplate.opsForValue().set(cacheKey, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //3.数据库数据写入Redis
            //手动序列化
            String shopStr = JSONUtil.toJsonStr(shop);
            stringRedisTemplate.opsForValue().set(cacheKey, shopStr, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            unLock(lockKey);
        }

        return shop;
    }

    /**
     * 获取互斥锁
     */
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放互斥锁
     */
    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }

    /**
     * 根据商铺id查询店铺数据，并将数据封装逻辑过期时间，保存到缓存中（缓存预热、重建缓存使用）
     * - 逻辑过期时间根据具体业务而定，逻辑过期过长，会造成缓存数据的堆积，浪费内存；过短造成频繁缓存重建，降低性能。
     * - 所以设置逻辑过期时间时，需要实际测试和评估不同参数下的性能和资源消耗情况，可以通过观察系统的表现，在业务需求和性能要求之间找到一个平衡点
     * @param id 商铺id
     * @param expireSeconds 有效期（单位：秒）
     */
    public void saveShop2Cache(Long id, Long expireSeconds) {
        // 查询店铺数据
        Shop shop = getById(id);
        // 封装逻辑过期数据（热点数据）
        RedisData redisData = new RedisData();
        redisData.setData(shop);    // 设置缓存数据
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));    // 设置逻辑过期时间=当前时间+有效期TTL
        // 将逻辑过期数据写入Redis，不设置TTL过期时间，key永久有效，真正的过期时间为逻辑过期时间
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        // 更新数据库
        updateById(shop);
        // 删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + id);
        return Result.ok();
    }
}
