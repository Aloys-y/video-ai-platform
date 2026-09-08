package com.videoai.worker.ownership;
import com.videoai.common.analysis.ExecutionOwnership.Token;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service @lombok.extern.slf4j.Slf4j
public class TaskLeaseService {
 private final JdbcTemplate jdbc;private final PlatformTransactionManager transactions;
 public static final int LEASE_SECONDS=90;
 private final java.util.concurrent.atomic.LongAdder renewalFailures=new java.util.concurrent.atomic.LongAdder();
 public TaskLeaseService(JdbcTemplate source,PlatformTransactionManager transactions){
  this.jdbc=new JdbcTemplate(Objects.requireNonNull(source.getDataSource()));
  this.jdbc.setQueryTimeout(5);this.transactions=transactions;
 }
 public long renewalFailures(){return renewalFailures.sum();}
 public void verifySchema(){jdbc.queryForList("SELECT execution_owner,execution_lease_until,execution_heartbeat_at FROM analysis_task WHERE 1=0");}
 public Token claim(String taskId,int no) {
  long began=System.nanoTime();String owner=UUID.randomUUID().toString();
  int changed=jdbc.update("UPDATE analysis_task SET execution_owner=?,execution_lease_until=TIMESTAMPADD(SECOND,90,NOW(3)),execution_heartbeat_at=NOW(3) WHERE task_id=? AND retry_count=? AND status IN ('PENDING','QUEUED','PROCESSING') AND (execution_owner IS NULL OR execution_lease_until<=NOW(3))",owner,taskId,no);
  return changed==1?new Token(taskId,no,owner,began+TimeUnit.SECONDS.toNanos(LEASE_SECONDS)):null;
 }
 public boolean settled(String taskId,int no) {
  var rows=jdbc.queryForList("SELECT status,retry_count FROM analysis_task WHERE task_id=?",taskId);
  if(rows.isEmpty())return true;
  var r=rows.get(0);int current=((Number)r.get("retry_count")).intValue();
  if(current>no)return true;if(current<no)return false;
  return Set.of("COMPLETED","PARTIALLY_COMPLETED","FAILED","DEAD","CANCELLED").contains(r.get("status"));
 }
 public void renew(Token token) {
  long began=System.nanoTime();
  try {
   token.check();
   int n=jdbc.update("UPDATE analysis_task SET execution_lease_until=TIMESTAMPADD(SECOND,90,NOW(3)),execution_heartbeat_at=NOW(3) WHERE task_id=? AND retry_count=? AND execution_owner=? AND execution_lease_until>NOW(3) AND status IN ('PENDING','QUEUED','PROCESSING')",token.taskId,token.executionNo,token.owner);
   if(n!=1){token.invalidate();renewalFailures.increment();log.warn("执行权续租被拒绝: taskId={}",token.taskId);}
   else token.renewed(began+TimeUnit.SECONDS.toNanos(LEASE_SECONDS));
  } catch(Exception e) {token.invalidate();renewalFailures.increment();log.warn("执行权续租失败，停止新调用和写入: taskId={}, type={}",token.taskId,e.getClass().getSimpleName());}
 }
 public void release(Token token) {
  token.invalidate();
  jdbc.update("UPDATE analysis_task SET execution_owner=NULL,execution_lease_until=NULL WHERE task_id=? AND execution_owner=?",token.taskId,token.owner);
 }
 /** 同一事务中锁住任务行并校验执行权，再执行实际Mapper写入，防止check-then-write竞争。 */
 public Object fenced(Token token, java.util.concurrent.Callable<Object> action) {
  var tx=new TransactionTemplate(transactions);tx.setTimeout(10);
  return tx.execute(status->{
   try {
    token.check();
    var rows=jdbc.queryForList("SELECT task_id FROM analysis_task WHERE task_id=? AND retry_count=? AND execution_owner=? AND execution_lease_until>NOW(3) AND status<>'CANCELLED' FOR UPDATE",token.taskId,token.executionNo,token.owner);
    if(rows.size()!=1)throw new IllegalStateException("执行令牌、租约或任务状态失效");
    token.check();return action.call();
   } catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException("受保护写入失败",e);}
  });
 }
}
