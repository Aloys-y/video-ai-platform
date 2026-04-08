package com.videoai.infra.mysql.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videoai.common.domain.UploadSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 上传会话Mapper
 *
 * 面试重点：
 * 1. 为什么继承BaseMapper？
 *    - 自动拥有CRUD方法，无需写XML
 *    - 支持条件构造器LambdaQueryWrapper
 *
 * 2. 自定义方法为什么用@Update注解？
 *    - 简单SQL直接写在注解里
 *    - 复杂SQL再写XML文件
 */
@Mapper
public interface UploadSessionMapper extends BaseMapper<UploadSession> {

    /**
     * 原子性添加已上传分片
     *
     * 面试点：为什么用自定义SQL？
     * MyBatis-Plus的update方法不支持JSON数组操作
     * 需要用MySQL的JSON_ARRAY_APPEND函数
     *
     * 性能优化：
     * 先检查是否已包含，避免重复添加
     */
    @Update("UPDATE upload_session SET " +
            "uploaded_chunks = CASE " +
            "  WHEN JSON_CONTAINS(uploaded_chunks, CAST(#{chunkIndex} AS JSON)) " +
            "  THEN uploaded_chunks " +
            "  ELSE JSON_ARRAY_APPEND(uploaded_chunks, '$', #{chunkIndex}) " +
            "END, " +
            "updated_at = NOW() " +
            "WHERE upload_id = #{uploadId} AND status = 0")
    int appendUploadedChunk(@Param("uploadId") String uploadId,
                           @Param("chunkIndex") Integer chunkIndex);

    /**
     * 更新会话状态
     */
    @Update("UPDATE upload_session SET status = #{status}, updated_at = NOW() " +
            "WHERE upload_id = #{uploadId}")
    int updateStatus(@Param("uploadId") String uploadId, @Param("status") Integer status);

    /**
     * 设置存储路径（合并完成后调用）
     */
    @Update("UPDATE upload_session SET storage_path = #{storagePath}, " +
            "status = 2, updated_at = NOW() " +
            "WHERE upload_id = #{uploadId}")
    int setStoragePath(@Param("uploadId") String uploadId,
                       @Param("storagePath") String storagePath);

    /**
     * 批量过期过期的上传会话
     * 定时任务调用，清理超过24小时未完成的上传
     */
    @Update("UPDATE upload_session SET status = 3, updated_at = NOW() " +
            "WHERE status = 0 AND expired_at < NOW()")
    int expireTimedOutSessions();
}
