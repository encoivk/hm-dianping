package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.constants.CacheConstants;
import com.hmdp.constants.RedisConstants;
import com.hmdp.constants.SystemConstants;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheUtils;
import com.hmdp.utils.RedisData;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;


@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    StringRedisTemplate stringRedisTemplate;
    @Resource
    ObjectMapper objectMapper;
    @Resource
    CacheUtils cacheUtils;
    @Override
    public Result queryById(Long id) throws JsonProcessingException {
        //缓存击穿
//        Shop shop=cacheUtils.queryWithPassThrough(
//                RedisConstants.CACHE_SHOP_KEY,id, Shop.class,
//                this::getById,RedisConstants.CACHE_SHOP_TTL,TimeUnit.MINUTES
//        );
        // 互斥锁解决缓存击穿
//         Shop shop = cacheUtils
//                 .queryWithMutex(RedisConstants.CACHE_SHOP_KEY, id, Shop.class, this::getById,
//                         RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 逻辑过期解决缓存击穿
         Shop shop = cacheUtils
                 .queryWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY, id, Shop.class,
                         this::getById, 20L, TimeUnit.SECONDS);
        if(shop==null)
        {
            return Result.fail("店铺不存在!!!");
        }
        return Result.success(shop);
    }

    @Override
    @Transactional
    public Result updateShop(Shop shop) {
        Long id=shop.getId();
        if(id==null)
        {
            return Result.fail("店铺id不能为空");
        }
        this.updateById(shop);
        stringRedisTemplate.delete(CacheConstants.SHOP_CACH+id);
        return Result.success();
    }

    private ThreadPoolExecutor THREAD_POOL=new ThreadPoolExecutor(
            10,12,12,
            TimeUnit.SECONDS,new ArrayBlockingQueue<>(4),
            Executors.defaultThreadFactory(),
            new ThreadPoolExecutor.CallerRunsPolicy()
    );
    //缓存击穿
    @Override
    public Result queryWithPassThrough(Long id) throws JsonProcessingException {
        String key= CacheConstants.SHOP_CACH+id;
        String s = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(s))
        {
            Shop shop=objectMapper.readValue(s,Shop.class);
            return Result.success(shop);
        }
        //判断是否为缓存的空串
        if(s!=null)
        {
            return Result.fail("店铺不存在!!!");
        }
        Shop shop=this.getById(id);
        //缓存空串
        if(shop==null)
        {
            stringRedisTemplate.opsForValue().set(CacheConstants.SHOP_CACH+id,"",
                    RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return Result.fail("店铺不存在!!!");
        }
        //写入缓存
        s= objectMapper.writeValueAsString(shop);
        stringRedisTemplate.opsForValue().set(key,s);
        return Result.success(shop);
    }

    /****互斥锁解决缓存击穿****/
    @Override
    public Result queryWithMutex(Long id) throws JsonProcessingException {
        String cacheKey=RedisConstants.CACHE_SHOP_KEY+id;
        String shopJSON = stringRedisTemplate.opsForValue().get(cacheKey);
        //查到直接返回
        if(StrUtil.isNotBlank(shopJSON))
        {
            Shop shop=objectMapper.readValue(shopJSON, Shop.class);
            return Result.success(shop);
        }
        //判断是否为缓存的空串(解决缓存穿透)
        if(shopJSON!=null)
        {
            return Result.fail("店铺不存在!!!");
        }
        //缓存未命中，重建缓存
        Shop shop=null;
        try {
            //获取互斥锁
            boolean isLock=tryLock(id);
            if(!isLock)
            {
                //获取失败
                return queryWithMutex(id);
            }
            //获取成功，重建缓存
            shop = this.getById(id);
            //缓存空串(解决缓存穿透)
            if(shop==null)
            {
                stringRedisTemplate.opsForValue().set(cacheKey,"",
                        RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return Result.fail("店铺不存在!!!");
            }
            //写入缓存
            shopJSON= objectMapper.writeValueAsString(shop);
            stringRedisTemplate.opsForValue().set(cacheKey,shopJSON);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        finally {
            //释放互斥锁
            this.unLock(id);
        }
        return Result.success(shop);

    }

    /****逻辑过期解决缓存击穿****/
    @Override
    public Result queryWithLogicalExpire(Long id) throws JsonProcessingException {
        String cacheKey=RedisConstants.CACHE_SHOP_KEY+id;
        String dataJSON = stringRedisTemplate.opsForValue().get(cacheKey);
        if(StrUtil.isBlank(dataJSON))
        {
            //不存在，直接返回(逻辑过期已有值不存在空)
            return Result.fail("店铺不存在");
        }
        //反序列化
        RedisData redisData=objectMapper.readValue(dataJSON, RedisData.class);
        Shop shop = BeanUtil.mapToBean((LinkedHashMap)redisData.getData(), Shop.class,true);
        LocalDateTime expireTime = redisData.getExpireTime();
        if(LocalDateTime.now().isBefore(expireTime))
        {
            //没过期，直接返回shop
            return Result.success(shop);
        }
        try {
            //获取互斥锁
            boolean isLock=this.tryLock(id);
            if(!isLock)
            {
                //被锁了，直接返回
                return Result.success(shop);
            }
            //重建缓存
            this.THREAD_POOL.submit(()->{
                this.reCache(id,20L);
                try {
                    //Thread.sleep(200);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            this.unLock(id);
        }
        return Result.success(shop);
    }
    @Override
    public void unLock(Long id) {
        stringRedisTemplate.delete(RedisConstants.LOCK_SHOP_KEY + id);
    }

    @Override
    public boolean tryLock(Long id) {
        Boolean delete = stringRedisTemplate.opsForValue().setIfAbsent(
                RedisConstants.LOCK_SHOP_KEY + id,"1",RedisConstants.LOCK_SHOP_TTL,TimeUnit.SECONDS
        );
        return BooleanUtil.isTrue(delete);
    }

    //重构缓存
    public void reCache(Long id, Long expireSecond)
    {
        try {
            Shop shop=this.getById(id);
            RedisData redisData=RedisData.builder()
                    .data(shop)
                    .expireTime(LocalDateTime.now().plusSeconds(expireSecond))
                    .build();
            String dataJSON=objectMapper.writeValueAsString(redisData);
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id,dataJSON);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        if (x==null || y==null) {
            // 根据类型分页查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.success(page.getRecords());
        }
        // 计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        String key=RedisConstants.SHOP_GEO_KEY+typeId;
        // 查询redis、按照距离排序、分页。结果：shopId、distance
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(  // GEOSEARCH key BYLONLAT x y BYRADIUS 10 WITHDISTANCE
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs
                                .newGeoSearchArgs().includeDistance().limit(end));
        if(results==null) {
            return Result.success(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            // 没有下一页了，结束
            return Result.success(Collections.emptyList());
        }
        // 截取 from ~ end的部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            // 获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            // 获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        // 根据id查询Shop,并且为Shop赋值distant字段
        String idStr = StrUtil.join(",", ids);
        //使用StringJoiner将数组以逗号分隔
//        StringJoiner stringJoiner=new StringJoiner(",");
//        for(Long id:ids)
//        {
//            stringJoiner.add(id.toString());
//        }
//        String idStr=stringJoiner.toString();
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        // 6.返回
        return Result.success(shops);
    }
}
