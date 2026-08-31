package com.videoai.infra.mysql.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videoai.common.domain.TaskOutbox;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务Outbox Mapper
 */
@Mapper
public interface TaskOutboxMapper extends BaseMapper<TaskOutbox> {

    @Select("SELECT * FROM task_outbox WHERE status = 'NEW' " +
            "AND available_at <= NOW(3) ORDER BY available_at ASC, id ASC LIMIT #{limit}")
    List<TaskOutbox> selectReadyToDispatch(@Param("limit") int limit);

    @Update("UPDATE task_outbox SET status = 'SENDING', updated_at = NOW(3) " +
            "WHERE id = #{id} AND status = 'NEW'")
    int markSending(@Param("id") Long id);

    @Update("UPDATE task_outbox SET status = 'SENT', sent_at = NOW(3), updated_at = NOW(3) " +
            "WHERE id = #{id} AND status = 'SENDING'")
    int markSent(@Param("id") Long id);

    @Update("UPDATE task_outbox SET status = 'NEW', send_attempt_count = send_attempt_count + 1, " +
            "last_error = #{lastError}, available_at = #{availableAt}, updated_at = NOW(3) " +
            "WHERE id = #{id} AND status = 'SENDING'")
    int markSendFailed(@Param("id") Long id,
                       @Param("lastError") String lastError,
                       @Param("availableAt") LocalDateTime availableAt);

    @Update("UPDATE task_outbox SET status = 'NEW', updated_at = NOW(3) " +
            "WHERE status = 'SENDING' AND updated_at < #{deadline}")
    int recoverStuckSending(@Param("deadline") LocalDateTime deadline);

}
