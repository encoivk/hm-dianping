package com.hmdp.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IShopService extends IService<Shop> {

    Result queryById(Long id) throws JsonProcessingException;

    Result queryWithPassThrough(Long id) throws JsonProcessingException;

    Result updateShop(Shop shop);

    Result queryWithMutex(Long id) throws JsonProcessingException;

    Result queryWithLogicalExpire(Long id) throws JsonProcessingException;

    void unLock(Long id);
    void reCache(Long id, Long expireSecond);

    boolean tryLock(Long id);

    Result queryShopByType(Integer typeId, Integer current, Double x, Double y);
}
