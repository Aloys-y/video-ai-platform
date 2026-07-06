package com.videoai.infra.mysql.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videoai.common.domain.KnowledgeChunk;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface KnowledgeChunkMapper extends BaseMapper<KnowledgeChunk> {

    @Select("SELECT * FROM knowledge_chunk WHERE base_code = #{baseCode} AND card_code = #{cardCode} ORDER BY chunk_no ASC")
    List<KnowledgeChunk> selectByCardCode(@Param("baseCode") String baseCode, @Param("cardCode") String cardCode);

    @Delete("DELETE FROM knowledge_chunk WHERE base_code = #{baseCode} AND card_code = #{cardCode}")
    int deleteByCardCode(@Param("baseCode") String baseCode, @Param("cardCode") String cardCode);
}
