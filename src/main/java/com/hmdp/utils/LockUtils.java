package com.hmdp.utils;

public interface LockUtils {

    boolean tryLock(Long expireTime, String lockName, String lockId);

    void unLock();


}
