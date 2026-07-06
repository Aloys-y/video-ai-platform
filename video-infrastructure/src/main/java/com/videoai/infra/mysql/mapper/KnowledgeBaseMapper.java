package com.videoai.infra.mysql.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videoai.common.domain.KnowledgeBase;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface KnowledgeBaseMapper extends BaseMapper<KnowledgeBase> {

    @Select("SELECT * FROM knowledge_base WHERE base_code = #{baseCode} LIMIT 1")
    KnowledgeBase selectByBaseCode(@Param("baseCode") String baseCode);

    @Update("UPDATE knowledge_base SET current_version_tag = #{versionTag}, updated_at = NOW() WHERE base_code = #{baseCode}")
    int updateCurrentVersion(@Param("baseCode") String baseCode, @Param("versionTag") String versionTag);
}
