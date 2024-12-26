package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.constants.RedisConstants;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Resource
    StringRedisTemplate stringRedisTemplate;
    @Resource
    IUserService userService;
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {  //关注或者取关
        Long userId=UserHolder.getUser().getId();
        String key= RedisConstants.FOLLOW+userId;
        if(isFollow)
        {
            //关注，新增数据
            Follow follow=new Follow();
            follow.setFollowUserId(followUserId);
            follow.setUserId(userId);
            //更新缓存里的set
            stringRedisTemplate.opsForSet().add(key, String.valueOf(followUserId));
            save(follow);
        }
        else
        {
            //取关，删除数据
            stringRedisTemplate.opsForSet().remove(key, String.valueOf(followUserId));
            remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId).eq("follow_user_id", followUserId));
        }
        return Result.success();
    }

    @Override
    public Result isFollow(Long followUserId) {
        Long userId= UserHolder.getUser().getId();
        Integer count = query().eq("follow_user_id", followUserId).eq("user_id",userId).count();
        return Result.success(count>0);
    }

    @Override
    public List<UserDTO> followCommons(Long id) {
        Long userId=UserHolder.getUser().getId();
        String key1= RedisConstants.FOLLOW+userId;
        String key2= RedisConstants.FOLLOW+id;
        //求交集
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key1, key2);
        if(intersect==null || intersect.isEmpty())
        {
            return Collections.emptyList();
        }
        //查询交集user信息
        List<Long> list = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        List<User> userList = userService.listByIds(list);
        //封装为userDTO
        List<UserDTO> dtoList = userList.stream().map((user) -> {
            UserDTO userDTO = new UserDTO();
            BeanUtils.copyProperties(user, userDTO);
            return userDTO;
        }).collect(Collectors.toList());

        return dtoList;
    }
}
