package com.hmdp.utils;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class RedisLockUtils implements LockUtils{

    StringRedisTemplate stringRedisTemplate;
    private final String KEY_PREFIX="lock:";
    private String keyPrefix;
    private String value;

    public RedisLockUtils(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(Long expireTime, String lockName, String lockId) {
        this.keyPrefix=this.KEY_PREFIX+lockName+":"+lockId;
        this.value= UUID.randomUUID().toString()+Thread.currentThread().getName();
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(
                keyPrefix, value, expireTime, TimeUnit.SECONDS
        );
        //防止自动拆箱可能导致的NullPointer问题
        return Boolean.TRUE.equals(success);
    }

//    @Override
//    public boolean unLock() {
//        String s = stringRedisTemplate.opsForValue().get(this.KEY_PREFIX);
//        if(s==null||!s.equals(this.value))
//        {
//            return false;
//        }
//        Boolean success = stringRedisTemplate.delete(keyPrefix);
//        //防止自动拆箱可能导致的NullPointer问题
//        return Boolean.TRUE.equals(success);
//    }

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    @Override
    public void unLock() {
        // 调用lua脚本
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(keyPrefix),
                this.value);
    }
}
