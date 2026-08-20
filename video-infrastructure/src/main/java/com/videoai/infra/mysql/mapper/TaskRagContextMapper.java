package com.videoai.infra.mysql.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videoai.common.domain.TaskRagContext;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TaskRagContextMapper extends BaseMapper<TaskRagContext> {

    @Select("SELECT * FROM task_rag_context WHERE task_id = #{taskId} ORDER BY created_at DESC LIMIT 1")
    TaskRagContext selectLatestByTaskId(@Param("taskId") String taskId);
}
