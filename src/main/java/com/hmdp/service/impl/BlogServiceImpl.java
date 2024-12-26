package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.constants.RedisConstants;
import com.hmdp.constants.SystemConstants;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
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
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    IUserService userService;
    @Resource
    StringRedisTemplate stringRedisTemplate;
    @Resource
    IFollowService followService;
    @Override
    public Result queryBlogById(Long id) {
        Blog blog = this.getById(id);
        User user = userService.getById(blog.getUserId());
        blog.setIcon(user.getIcon());
        blog.setName(user.getNickName());
        blog.setIsLike(isLiked(id));
        return Result.success(blog);
    }

    @Override
    public List<Blog> queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog ->{
            Long userId = blog.getUserId();
            User user = userService.getById(userId);
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());
            blog.setIsLike(isLiked(blog.getId()));
        });
        return records;
    }

    @Override
    public boolean likeBlog(Long id) {
        //获取登录用户
        Long userId = UserHolder.getUser().getId();
        //判断是否点赞
        boolean liked = isLiked(id);
        if(!liked)
        {
            //添加用户id
            stringRedisTemplate.opsForZSet().add(RedisConstants.BLOG_LIKED_KEY +id,
                    String.valueOf(userId),System.currentTimeMillis());
            // 修改点赞数量
            update().setSql("liked = liked + 1").eq("id", id).update();
        }
        else
        {
            //删除用户id
            stringRedisTemplate.opsForZSet().remove(RedisConstants.BLOG_LIKED_KEY +id, String.valueOf(userId));
            // 修改点赞数量
            update().setSql("liked = liked - 1").eq("id", id).update();
        }
        return !liked;
    }

    private boolean isLiked(Long id) //判断用户是否点赞
    {
        UserDTO user = UserHolder.getUser();
        //用户未登录
        if(user==null)return false;
        //获取用户id
        Long userId=user.getId();
        String key= RedisConstants.BLOG_LIKED_KEY +id;
        Double score = stringRedisTemplate.opsForZSet().score(key,String.valueOf(userId));
        return score!=null;
    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key=RedisConstants.BLOG_LIKED_KEY+id;
        Set<String> userSet = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if(userSet==null||userSet.isEmpty())
        {
            return Result.success(Collections.emptyList());
        }
        // 解析出其中的用户id
        List<Long> ids = userSet.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        // 根据用户id查询用户 WHERE id IN ( 5 , 1 ) ORDER BY FIELD(id, 5, 1)
        List<UserDTO> userDTOS = userService.query()
                .in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        // 返回
        return Result.success(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        Long userId=user.getId();
        blog.setUserId(userId);
        // 保存探店blog
        boolean success = save(blog);
        if(!success)
        {
            log.error("保存blog失败");
        }
        //推送至粉丝收件箱
        List<Follow> follows = followService.query().eq("follow_user_id", userId).list();
        for(Follow follow:follows)
        {
            String key=RedisConstants.FEED_KEY+follow.getUserId();
            stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());
        }
        return Result.success(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {  // 滚动分页查询
        //获取当前用户
        Long userId=UserHolder.getUser().getId();
        String key=RedisConstants.FEED_KEY+userId;
        //查收件箱  ZREVRANGEBYSCORE key Max Min LIMIT offset count
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        //判空
        if(typedTuples==null || typedTuples.isEmpty())
        {
            return Result.success();
        }
        List<Long> ids=new ArrayList<>(typedTuples.size());
        //设置offset 以及 minTime
        Integer offs=1;
        Long minTime=0L;
        for(ZSetOperations.TypedTuple<String> tuple:typedTuples)
        {
            ids.add(Long.valueOf(tuple.getValue()));
            Long time=tuple.getScore().longValue();
            if(minTime.equals(time))
            {
                offs++;
            }
            else {
                minTime=time;
                offs=1;
            }
        }
        offs = minTime == max ? offs : offs + offset;
        // 根据id查询blog
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();

        for (Blog blog : blogs) {
            // 查询blog有关的用户
            User user = userService.getById(blog.getUserId());
            blog.setIcon(user.getIcon());
            blog.setName(user.getNickName());
            // 查询blog是否被点赞
            blog.setIsLike(isLiked(userId));
        }

        // 封装并返回
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setOffset(offs);
        r.setMinTime(minTime);

        return Result.success(r);
    }


}
