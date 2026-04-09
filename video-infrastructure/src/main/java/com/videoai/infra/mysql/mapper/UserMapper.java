package com.videoai.infra.mysql.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videoai.common.domain.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 用户Mapper
 *
 * 面试重点：
 * 1. 继承BaseMapper获得基础CRUD
 * 2. 复杂查询用@Select注解
 * 3. 性能敏感的查询走Redis缓存
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

    /**
     * 根据API Key查询用户
     * 这是认证时最常用的查询，需要加索引
     */
    @Select("SELECT * FROM user WHERE api_key = #{apiKey} AND status = 1")
    User selectByApiKey(@Param("apiKey") String apiKey);

    /**
     * 根据用户ID查询
     */
    @Select("SELECT * FROM user WHERE user_id = #{userId}")
    User selectByUserId(@Param("userId") String userId);

    /**
     * 禁用用户
     */
    @Update("UPDATE user SET status = 0, updated_at = NOW() WHERE id = #{id}")
    int disableUser(@Param("id") Long id);

    /**
     * 更新限流阈值
     */
    @Update("UPDATE user SET rate_limit = #{rateLimit}, updated_at = NOW() WHERE id = #{id}")
    int updateRateLimit(@Param("id") Long id, @Param("rateLimit") Integer rateLimit);
}
