package com.videoai.infra.notification;

import com.videoai.common.domain.AnalysisFinishedEvent;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;

/** 仅供 Mock 验证，无网络发信实现，也不需要真实邮箱。 */
public final class MockMailService {
    public enum Outcome { SUCCESS, TRANSIENT_FAILURE, PERMANENT_FAILURE, UNKNOWN }
    public interface Provider { Outcome send(String eventId, String subject, String body); }
    private final JdbcTemplate jdbc;
    private final Provider provider;
    public MockMailService(JdbcTemplate jdbc, Provider provider) { this.jdbc=jdbc;this.provider=provider; }
    public void accept(AnalysisFinishedEvent event) {
        event.validate();
        String label=switch(event.status()) {case "SUCCEEDED" -> "已完成";case "PARTIAL" -> "部分完成";default -> "失败";};
        try {
            jdbc.update("INSERT INTO mock_mail_notification(event_id,task_id,execution_no,subject,body,status) VALUES(?,?,?,?,?,'PENDING')",
                event.eventId(),event.taskId(),event.executionNo(),"[模拟邮件] 视频分析"+label,
                "任务 "+event.taskId()+"，第 "+(event.executionNo()+1)+" 次执行"+label+"。查看结果：/#/task/"+event.taskId()+"（未发送真实邮件）");
        } catch(DuplicateKeyException duplicate) { /* 已入库即可确认，重复事件不覆盖发送状态。 */ }
    }
    public void tick() {
        // Mock 不存在外部副作用；过期发送仍标 UNKNOWN，演练未来真实发送的不确定边界。
        jdbc.update("UPDATE mock_mail_notification SET status='UNKNOWN',error_code='CLAIM_EXPIRED',claim_token=NULL,claim_until=NULL WHERE status='SENDING' AND claim_until<CURRENT_TIMESTAMP");
        var rows=jdbc.queryForList("SELECT event_id,subject,body FROM mock_mail_notification WHERE status='PENDING' AND next_attempt_at<=CURRENT_TIMESTAMP ORDER BY created_at LIMIT 2");
        for(var row:rows) {
            String id=(String)row.get("event_id"), token=UUID.randomUUID().toString();
            int claimed=jdbc.update("UPDATE mock_mail_notification SET status='SENDING',attempt_count=attempt_count+1,claim_token=?,claim_until=TIMESTAMPADD(SECOND,120,CURRENT_TIMESTAMP) WHERE event_id=? AND status='PENDING' AND next_attempt_at<=CURRENT_TIMESTAMP",token,id);
            if(claimed!=1) continue;
            Outcome result;
            try {result=Objects.requireNonNull(provider.send(id,(String)row.get("subject"),(String)row.get("body")));}
            catch(RuntimeException e) {result=Outcome.UNKNOWN;}
            if(result==Outcome.TRANSIENT_FAILURE) {
                jdbc.update("UPDATE mock_mail_notification SET status=CASE WHEN attempt_count>=3 THEN 'FAILED' ELSE 'PENDING' END,next_attempt_at=TIMESTAMPADD(SECOND,CASE WHEN attempt_count=1 THEN 60 ELSE 300 END,CURRENT_TIMESTAMP),error_code='TRANSIENT_FAILURE',claim_token=NULL,claim_until=NULL WHERE event_id=? AND claim_token=? AND status='SENDING'",id,token);
            } else {
                String status=switch(result) {case SUCCESS -> "MOCK_SENT";case PERMANENT_FAILURE -> "FAILED";default -> "UNKNOWN";};
                jdbc.update("UPDATE mock_mail_notification SET status=?,error_code=?,claim_token=NULL,claim_until=NULL WHERE event_id=? AND claim_token=? AND status='SENDING'",status,result==Outcome.SUCCESS?null:result.name(),id,token);
            }
        }
    }
    public List<Map<String,Object>> list(String taskId) {
        return jdbc.queryForList("SELECT event_id,execution_no,subject,body,status,attempt_count,error_code,created_at FROM mock_mail_notification WHERE task_id=? ORDER BY created_at DESC LIMIT 50",taskId);
    }
}
