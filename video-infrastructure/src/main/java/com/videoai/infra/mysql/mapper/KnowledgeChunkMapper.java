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

    @Select("""
            SELECT chunk.*
            FROM knowledge_chunk chunk
            INNER JOIN knowledge_card card
              ON card.base_code = chunk.base_code AND card.card_code = chunk.card_code
            WHERE chunk.base_code = #{baseCode}
              AND chunk.index_status = 'INDEXED'
              AND card.enabled = 1
              AND card.category = 'LEGEND'
              AND (card.timeless = 1 OR card.version_tag = #{versionTag})
            ORDER BY chunk.id ASC
            """)
    List<KnowledgeChunk> selectForRetrieval(@Param("baseCode") String baseCode,
                                            @Param("versionTag") String versionTag);
}
