package com.videoai.worker.scheduler;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** 数据库任务仓储：条件领取、续租、结果写入保护与过期失败。 */
public final class TaskDispatchRepository {
    public record Candidate(String taskId, int attemptNo) {}
    public record Lease(String taskId, int attemptNo, String owner) {}
    private static final Set<String> TERMINAL = Set.of("SUCCEEDED", "PARTIAL", "FAILED");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final int leaseSeconds;
    private final java.util.function.Consumer<com.videoai.common.domain.AnalysisFinishedEvent> events;

    public int leaseSeconds() { return leaseSeconds; }

    public TaskDispatchRepository(DataSource source, PlatformTransactionManager manager, int leaseSeconds) {
        this(source, manager, leaseSeconds, event -> {});
    }
    public TaskDispatchRepository(DataSource source, PlatformTransactionManager manager, int leaseSeconds,
            java.util.function.Consumer<com.videoai.common.domain.AnalysisFinishedEvent> events) {
        if (leaseSeconds < 1 || leaseSeconds > 3600) throw new IllegalArgumentException("租约秒数无效");
        this.jdbc = new JdbcTemplate(source);
        this.jdbc.setQueryTimeout(5);
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setTimeout(10);
        this.leaseSeconds = leaseSeconds;
        this.events = events;
    }

    public List<Candidate> candidates(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("候选数量无效");
        return jdbc.query("SELECT task_id,attempt_no FROM analysis_task WHERE status='PENDING' "
                        + "ORDER BY created_at,task_id LIMIT ?",
                (rs, index) -> new Candidate(rs.getString(1), rs.getInt(2)), limit);
    }

    /** 空返回表示竞争失败；SQL异常表示结果可能未知，调用方不得开始业务。 */
    public Lease claim(Candidate candidate) {
        String owner = UUID.randomUUID().toString();
        int changed = jdbc.update("UPDATE analysis_task SET status='RUNNING',owner_token=?,"
                        + "lease_until=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(3)),started_at=CURRENT_TIMESTAMP(3) "
                        + "WHERE task_id=? AND attempt_no=? AND status='PENDING'",
                owner, leaseSeconds, candidate.taskId(), candidate.attemptNo());
        return changed == 1 ? new Lease(candidate.taskId(), candidate.attemptNo(), owner) : null;
    }

    public boolean renew(Lease lease) {
        return jdbc.update("UPDATE analysis_task SET lease_until=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(3)) "
                        + "WHERE task_id=? AND attempt_no=? AND owner_token=? AND status='RUNNING' "
                        + "AND lease_until>CURRENT_TIMESTAMP(3)",
                leaseSeconds, lease.taskId(), lease.attemptNo(), lease.owner()) == 1;
    }

    /** 仅供确定尚未开始业务的提交拒绝使用，不允许执行中异常退回重跑。 */
    public boolean releaseUnstarted(Lease lease) {
        return jdbc.update("UPDATE analysis_task SET status='PENDING',owner_token=NULL,lease_until=NULL,started_at=NULL "
                        + "WHERE task_id=? AND attempt_no=? AND owner_token=? AND status='RUNNING' "
                        + "AND lease_until>CURRENT_TIMESTAMP(3)",
                lease.taskId(), lease.attemptNo(), lease.owner()) == 1;
    }

    /** 父行校验与子结果写入在同一事务内；action必须是同数据源短写入，不能REQUIRES_NEW。 */
    public <T> T fenced(Lease lease, Supplier<T> action) {
        return transaction.execute(status -> {
            var rows = jdbc.queryForList("SELECT task_id FROM analysis_task WHERE task_id=? AND attempt_no=? "
                            + "AND owner_token=? AND status='RUNNING' AND lease_until>CURRENT_TIMESTAMP(3) FOR UPDATE",
                    lease.taskId(), lease.attemptNo(), lease.owner());
            if (rows.size() != 1) throw new IllegalStateException("任务执行权已失效");
            return action.get();
        });
    }

    public boolean finish(Lease lease, String terminal, String errorCode) {
        if (!TERMINAL.contains(terminal)) throw new IllegalArgumentException("不支持的执行终态");
        return transaction.execute(tx -> {
        int changed = jdbc.update("UPDATE analysis_task SET status=?,error_code=?,finished_at=CURRENT_TIMESTAMP(3),"
                        + "owner_token=NULL,lease_until=NULL WHERE task_id=? AND attempt_no=? AND owner_token=? "
                        + "AND status='RUNNING' AND lease_until>CURRENT_TIMESTAMP(3)",
                terminal, errorCode, lease.taskId(), lease.attemptNo(), lease.owner());
        if (changed == 1) emit(lease, terminal);
        return changed == 1;
        });
    }

    /** 只标记中断，不重新领取。成功片段记录不删除，后续可查询或用户主动重试。 */
    public int failExpired(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("清理数量无效");
        var expired = jdbc.query("SELECT task_id,attempt_no,owner_token FROM analysis_task "
                        + "WHERE status='RUNNING' AND lease_until<=CURRENT_TIMESTAMP(3) "
                        + "ORDER BY lease_until,task_id LIMIT ?",
                (rs, index) -> new Lease(rs.getString(1), rs.getInt(2), rs.getString(3)), limit);
        int changed = 0;
        for (var lease : expired) {
            changed += transaction.execute(tx -> {
            int count = jdbc.update("UPDATE analysis_task SET status='FAILED',error_code='EXECUTION_INTERRUPTED',"
                            + "finished_at=CURRENT_TIMESTAMP(3),owner_token=NULL,lease_until=NULL "
                            + "WHERE task_id=? AND attempt_no=? AND owner_token=? AND status='RUNNING' "
                            + "AND lease_until<=CURRENT_TIMESTAMP(3)",
                    lease.taskId(), lease.attemptNo(), lease.owner());
            if (count == 1) emit(lease, "FAILED");
            return count;
            });
        }
        return changed;
    }
    private void emit(Lease lease, String status) {
        // 注册提交后回调；通知失败不得使已经完成的任务表现为业务失败。
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
            new org.springframework.transaction.support.TransactionSynchronization() {
                @Override public void afterCommit() {
                    try { events.accept(com.videoai.common.domain.AnalysisFinishedEvent.of(lease.taskId(),lease.attemptNo(),status)); }
                    catch (RuntimeException e) { org.slf4j.LoggerFactory.getLogger(TaskDispatchRepository.class)
                        .warn("通知事件交接失败 taskId={} type={}",lease.taskId(),e.getClass().getSimpleName()); }
                }
            });
    }
}
