package com.hmdp;

import com.hmdp.constants.RedisConstants;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@SpringBootTest
class HmDianPingApplicationTests {
    @Autowired
    IShopService shopService;
    @Autowired
    CacheUtils cacheUtils;
    @Resource
    StringRedisTemplate stringRedisTemplate;
    @Test
    //初始化逻辑过期
    public void saveShopTest()
    {
        List<Shop> shopList=shopService.query().list();
        for(Shop shop:shopList)
        {
            cacheUtils.setWithLogicalExpire(
                    RedisConstants.CACHE_SHOP_KEY+shop.getId(),shop,RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS
            );
        }
    }

    @Test
    public void addGeo()  // 添加商户坐标到 Redis
    {
        Map<Long, List<Shop>> map = shopService.list().stream().collect(Collectors.groupingBy(Shop::getTypeId));
        for(Map.Entry<Long, List<Shop>> entry: map.entrySet())
        {
            Long typeId=entry.getKey();
            List<Shop> shopList=entry.getValue();
            for(Shop shop:shopList)
            {
                stringRedisTemplate.opsForGeo()
                        .add(RedisConstants.SHOP_GEO_KEY+typeId,
                                new Point(shop.getX(),shop.getY()),shop.getId().toString());
            }
        }
    }

}
