package com.videoai.infra.mysql.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videoai.common.domain.KnowledgeIndexJob;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.time.LocalDateTime;

@Mapper
public interface KnowledgeIndexJobMapper extends BaseMapper<KnowledgeIndexJob> {

    @Select("SELECT * FROM knowledge_index_job WHERE job_id = #{jobId} LIMIT 1")
    KnowledgeIndexJob selectByJobId(@Param("jobId") String jobId);

    @Select("SELECT * FROM knowledge_index_job WHERE status = 'NEW' ORDER BY created_at ASC LIMIT #{limit}")
    List<KnowledgeIndexJob> selectReadyToDispatch(@Param("limit") int limit);

    @Update("UPDATE knowledge_index_job SET status = 'QUEUED', queued_at = NOW(), updated_at = NOW() " +
            "WHERE id = #{id} AND status = 'NEW'")
    int markQueued(@Param("id") Long id);

    @Update("UPDATE knowledge_index_job SET status = 'PROCESSING', started_at = NOW(), updated_at = NOW() " +
            "WHERE job_id = #{jobId} AND status IN ('NEW', 'QUEUED')")
    int markProcessing(@Param("jobId") String jobId);

    @Update("UPDATE knowledge_index_job SET status = 'SUCCESS', total_chunks = #{totalChunks}, " +
            "success_chunks = #{successChunks}, failed_chunks = #{failedChunks}, error_message = NULL, " +
            "completed_at = NOW(), updated_at = NOW() WHERE job_id = #{jobId}")
    int markSuccess(@Param("jobId") String jobId,
                    @Param("totalChunks") Integer totalChunks,
                    @Param("successChunks") Integer successChunks,
                    @Param("failedChunks") Integer failedChunks);

    @Update("UPDATE knowledge_index_job SET status = 'FAILED', error_message = #{errorMessage}, " +
            "completed_at = NOW(), updated_at = NOW() WHERE job_id = #{jobId}")
    int markFailed(@Param("jobId") String jobId, @Param("errorMessage") String errorMessage);

    @Update("UPDATE knowledge_index_job SET status = 'NEW', queued_at = NULL, " +
            "error_message = 'Recovered after Kafka dispatch timeout', updated_at = NOW() " +
            "WHERE status = 'QUEUED' AND queued_at < #{cutoff}")
    int recoverStaleQueued(@Param("cutoff") LocalDateTime cutoff);

    @Update("UPDATE knowledge_index_job SET status = 'FAILED', " +
            "error_message = 'Index processing timed out', completed_at = NOW(), updated_at = NOW() " +
            "WHERE status = 'PROCESSING' AND " +
            "((job_type = 'REBUILD_ALL' AND started_at < #{rebuildCutoff}) OR " +
            "(job_type <> 'REBUILD_ALL' AND started_at < #{cardCutoff}))")
    int failStaleProcessing(@Param("cardCutoff") LocalDateTime cardCutoff,
                            @Param("rebuildCutoff") LocalDateTime rebuildCutoff);
}
