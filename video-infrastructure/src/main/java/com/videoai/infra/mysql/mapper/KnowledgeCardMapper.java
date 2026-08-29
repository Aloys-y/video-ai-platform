package com.videoai.infra.mysql.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videoai.common.domain.KnowledgeCard;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface KnowledgeCardMapper extends BaseMapper<KnowledgeCard> {

    @Select("SELECT * FROM knowledge_card WHERE base_code = #{baseCode} AND card_code = #{cardCode} LIMIT 1")
    KnowledgeCard selectByCardCode(@Param("baseCode") String baseCode, @Param("cardCode") String cardCode);

    @Select("SELECT * FROM knowledge_card WHERE base_code = #{baseCode} AND category = #{category} ORDER BY card_code ASC")
    List<KnowledgeCard> selectByCategory(@Param("baseCode") String baseCode,
                                         @Param("category") String category);

    @Select("SELECT * FROM knowledge_card WHERE base_code = #{baseCode} AND enabled = 1 " +
            "AND (timeless = 1 OR version_tag = #{versionTag}) ORDER BY updated_at DESC")
    List<KnowledgeCard> selectRetrievalCandidates(@Param("baseCode") String baseCode,
                                                  @Param("versionTag") String versionTag);

    @Select("SELECT * FROM knowledge_card WHERE base_code = #{baseCode} " +
            "AND index_status <> 'DRAFT' " +
            "AND (enabled = 0 OR timeless = 1 OR version_tag = #{versionTag}) ORDER BY updated_at DESC")
    List<KnowledgeCard> selectRebuildTargets(@Param("baseCode") String baseCode,
                                             @Param("versionTag") String versionTag);

    @Update("UPDATE knowledge_card SET index_status = #{indexStatus}, last_job_id = #{jobId}, " +
            "indexed_at = #{indexedAt}, updated_by = #{updatedBy}, updated_at = NOW() " +
            "WHERE base_code = #{baseCode} AND card_code = #{cardCode}")
    int updateIndexState(@Param("baseCode") String baseCode,
                         @Param("cardCode") String cardCode,
                         @Param("indexStatus") String indexStatus,
                         @Param("jobId") String jobId,
                         @Param("indexedAt") LocalDateTime indexedAt,
                         @Param("updatedBy") String updatedBy);
}
