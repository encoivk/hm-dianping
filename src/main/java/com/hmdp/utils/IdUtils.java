package com.hmdp.utils;

import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class IdUtils {
    private long beginTimeStamp= 1733743368L;
    private long COUNT_BITS= 32L;
    @Resource
    StringRedisTemplate stringRedisTemplate;
    public long getId(String prefix)  //生成全局唯一id
    {
        // 生成时间戳
        LocalDateTime now=LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timeStamp=nowSecond-this.beginTimeStamp;

        //生成序列号
        String date=now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        //利用Redis自增为每日订单计数
        long count=stringRedisTemplate.opsForValue().increment("icr:" + prefix + ":" + date);
        return timeStamp << COUNT_BITS | count;

    }
}
