package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissionConfig {
    @Bean
    public RedissonClient redissonClient()
    {
        // 配置
        Config config = new Config();
        config.useSingleServer().setAddress("redis://192.168.88.130:6379")
                .setPassword("353251");
        // 创建RedissonClient对象
        return Redisson.create(config);
    }
}
