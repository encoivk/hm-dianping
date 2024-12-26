package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {

    Result queryBlogById(Long id);

    List<Blog> queryHotBlog(Integer current);

    boolean likeBlog(Long id);

    Result queryBlogLikes(Long id);

    Result queryBlogOfFollow(Long max, Integer offset);

    Result saveBlog(Blog blog);
}
