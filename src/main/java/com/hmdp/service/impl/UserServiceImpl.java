package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.constants.CacheConstants;
import com.hmdp.constants.RedisConstants;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.constants.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;


@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Resource
    StringRedisTemplate stringRedisTemplate;
    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }
        // 3.符合，生成验证码
        String code = RandomUtil.randomNumbers(6);

        // 4.保存验证码到 Redis
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY+phone,code);
        stringRedisTemplate.expire(RedisConstants.LOGIN_CODE_KEY+phone,
                RedisConstants.LOGIN_CODE_TTL,TimeUnit.MINUTES);
        // 5.发送验证码
        log.debug("发送短信验证码成功，验证码: {"+code+"}");
        // 返回ok
        return Result.success();
    }

    @Override
    public Result login(LoginFormDTO loginForm) {
        //判断手机号
        String phone=loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone))
        {
            return Result.fail("手机号不合法");
        }
        //校验验证码
        String cachCode=stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY+phone);
        String userCode=loginForm.getCode();
        if(!cachCode.equals(userCode)||cachCode.equals(CacheConstants.CODE))
        {
            return Result.fail("验证码错误");
        }
        //生成随机token
        String token= UUID.randomUUID().toString();
        //保存或创建用户
        User user = this.query().eq("phone", loginForm.getPhone()).one();
        if(user==null)
        {
            user=this.createUserByPhone(loginForm.getPhone());
            this.save(user);
        }
        //以Map形式放入Redis
        UserDTO userDTO=new UserDTO();
        BeanUtils.copyProperties(user,userDTO);
        Map<String,String> map=new HashMap<>();
        map.put("id",String.valueOf(userDTO.getId()));
        map.put("icon",userDTO.getIcon());
        map.put("nickName",userDTO.getNickName());
        //放入Redis
        stringRedisTemplate.opsForHash().putAll(RedisConstants.LOGIN_USER_KEY+token,map);
        //设置token有效期
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY
                + token,RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        return Result.success(token);
    }

    @Override
    public void logout(HttpServletRequest request) {
        String token = request.getHeader(SystemConstants.TOKEN_HEADER);
        stringRedisTemplate.delete(RedisConstants.LOGIN_USER_KEY
                + token);
    }

    @Override
    public User createUserByPhone(String phone) {
        User user=new User();
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX +RandomUtil.randomString(10));
        user.setPhone(phone);
        return user;
    }

    @Override
    public void sign() {
        //获取登录用户
        Long userId = UserHolder.getUser().getId();
        //获取当前时间
        LocalDate now=LocalDate.now();
        //拼接key
        String key = RedisConstants.USER_SIGN_KEY+userId.toString()+now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        long offset=now.getDayOfMonth() -1;
        //修改BitMap
        stringRedisTemplate.opsForValue().setBit(key,offset,true);
    }

    @Override
    public Result signCount() {
        //获取登录用户
        Long userId = UserHolder.getUser().getId();
        //获取当前时间
        LocalDate now=LocalDate.now();
        //拼接key
        String key = RedisConstants.USER_SIGN_KEY+userId.toString()+now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        // 获取本月截止今天为止的所有的签到记录，返回的是一个十进制的数字 BITFIELD sign:5:202203 GET u14 0
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key, BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(now.getDayOfMonth())).valueAt(0)
        );
        //判空
        if(result==null || result.isEmpty())
        {
            return Result.success(0);
        }
        long data=result.get(0);
        int count=0;
        while (true)
        {
            if((data & 1) ==0) //任何数和 1做与运算得到的是最后一个二进制位数
            {
                break;
            }
            count++;
            data>>>=1; //'>>>'是无符号位移运算，左边填0
        }

        return Result.success(count);
    }
}
