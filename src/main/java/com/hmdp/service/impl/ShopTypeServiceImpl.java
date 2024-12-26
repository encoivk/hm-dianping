package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.constants.CacheConstants;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;


@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryTypeList() throws JsonProcessingException {
        String s=stringRedisTemplate.opsForValue().get(CacheConstants.SHOP_TYPE);
        if(StrUtil.isNotBlank(s))
        {
            List<ShopType> list=objectMapper.readValue(s,List.class);
            return Result.success(list);
        }
        List<ShopType> list=this.query().orderByAsc("sort").list();
        s = objectMapper.writeValueAsString(list);
        stringRedisTemplate.opsForValue().set(CacheConstants.SHOP_TYPE,s);
        return Result.success(list);
    }
}
