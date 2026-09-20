package com.videoai.infra.cost;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoai.common.analysis.*;
import com.videoai.common.domain.AiCallLog;
import com.videoai.infra.mysql.mapper.AiCallLogMapper;
import org.springframework.dao.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDateTime;
import java.util.*;

/** 每次写入独立短事务；不能放进模型重试循环中捕获后重新调用模型。 */
@Service
@lombok.extern.slf4j.Slf4j
public class AiCallRecorder {
    public enum Outcome { SUCCEEDED, FAILED, UNKNOWN, NOT_SENT }

    /** 只读取厂商 usage；不重复累计 video_tokens 等输入 Token 子项。 */
    public static AiUsage tokenUsage(com.fasterxml.jackson.databind.JsonNode usage) {
        if (usage == null || usage.isNull()) return AiUsage.unknown();
        return new AiUsage(tokenCount(usage, "prompt_tokens", "input_tokens", "inputTokens"),
                tokenCount(usage, "completion_tokens", "output_tokens", "outputTokens"), null, usage.toString());
    }

    /** Embedding/Rerank 只按输入计费；厂商仅返回 total_tokens 时按其输入用量使用。 */
    public static AiUsage inputUsage(com.fasterxml.jackson.databind.JsonNode usage) {
        if(usage==null || usage.isNull()) return AiUsage.unknown();
        Long input=tokenCount(usage,"input_tokens","prompt_tokens","inputTokens");
        if(input==null) input=tokenCount(usage,"total_tokens","totalTokens");
        return new AiUsage(input,null,null,usage.toString());
    }

    private static Long tokenCount(com.fasterxml.jackson.databind.JsonNode usage, String... aliases) {
        Long result = null;
        for (String alias : aliases) {
            var value = usage.get(alias);
            if (value == null || value.isNull()) continue;
            // 非整数、溢出或别名矛盾归入 INVALID_USAGE，不悄悄截断为较小费用。
            if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) return -1L;
            if (result != null && result.longValue() != value.longValue()) return -1L;
            result = value.longValue();
        }
        return result;
    }
    private final AiCallLogMapper mapper;
    private final AiCostCalculator calculator;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;

    public AiCallRecorder(AiCallLogMapper mapper, AiCostCalculator calculator,
                          ObjectMapper json, PlatformTransactionManager manager) {
        this.mapper=mapper;this.calculator=calculator;this.json=json;
        transaction=new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(5);
    }

    /** 调用方预先持有UUID；提交结果不确定时，以同一个ID核对，不能重新生成后发送。 */
    public String begin(String callId, AiCallContext context, String model) {
        uuid(callId); Objects.requireNonNull(context);
        if(model==null || !model.matches("[A-Za-z0-9_.:/-]{1,128}")) throw new IllegalArgumentException("无效模型ID");
        var old=mapper.find(callId);
        if(old!=null){sameOwner(old,context,model);return callId;}
        var row=new AiCallLog();row.setCallId(callId);row.setTaskId(context.taskId());row.setExecutionNo(context.executionNo());
        row.setStage(context.stage().name());row.setSubtaskNo(context.subtaskNo());row.setModel(model);
        row.setPriceSnapshot(calculator.snapshot(model));row.setStartedAt(LocalDateTime.now());
        try {transaction.executeWithoutResult(tx->mapper.insert(row));}
        catch(DuplicateKeyException e){sameOwner(required(mapper.find(callId)),context,model);}
        return callId;
    }

    public void bindRemoteTask(String callId, String remoteTaskId) {
        bindRemoteTask(callId,remoteTaskId,null);
    }

    public void bindRemoteTask(String callId, String remoteTaskId, String requestId) {
        uuid(callId);
        if(remoteTaskId==null || !remoteTaskId.matches("[A-Za-z0-9_-]{1,128}")) throw new IllegalArgumentException("无效远端ID");
        transaction.executeWithoutResult(tx->{
            var row=required(mapper.lock(callId));
            if(!row.getStage().equals("ASR")) throw new IllegalStateException("仅ASR允许绑定远端ID");
            if(row.getStatus().equals("NOT_SENT")) throw new IllegalStateException("未发送调用不能绑定远端ID");
            if(row.getRemoteTaskId()!=null && !row.getRemoteTaskId().equals(remoteTaskId)) throw new IllegalStateException("ASR远端ID冲突");
            String receiptId=safe(requestId);
            if(row.getRequestId()!=null && receiptId!=null && !row.getRequestId().equals(receiptId)) throw new IllegalStateException("ASR提交请求ID冲突");
            if(receiptId!=null)row.setRequestId(receiptId);
            row.setRemoteTaskId(remoteTaskId);mapper.bindRemote(row);
        });
    }

    /** 复用远端任务时仍归原代次，不为新的轮询创建费用行。 */
    public String findAsrCall(AiCallContext context,String remoteTaskId) {
        Objects.requireNonNull(context);
        if(context.stage()!=AiCallContext.Stage.ASR) throw new IllegalArgumentException("非ASR调用上下文");
        var rows=mapper.findAsrOrigin(context.taskId(),context.executionNo(),context.subtaskNo(),remoteTaskId);
        if(rows.size()!=1) throw new IllegalStateException("ASR原始调用记录缺失或不唯一，需要核对账本");
        return rows.get(0).getCallId();
    }

    /** 文件转写按厂商返回秒数计费，不能用本地音轨时长替代。 */
    public static AiUsage audioUsage(com.fasterxml.jackson.databind.JsonNode usage) {
        if(usage==null || usage.isNull()) return AiUsage.unknown();
        var duration=usage.get("duration");
        java.math.BigDecimal seconds=null;
        if(duration!=null && !duration.isNull()) {
            seconds=duration.isNumber()?duration.decimalValue():java.math.BigDecimal.valueOf(-1);
        }
        return new AiUsage(null,null,seconds,usage.toString());
    }

    /** false表示收尾落账暂不可用；已有模型响应仍可进入业务解析，禁止因此重调模型。 */
    public boolean finish(String callId, Outcome outcome, AiUsage usage, String requestId, String errorCode) {
        uuid(callId);Objects.requireNonNull(outcome);
        AiUsage actual=usage==null?AiUsage.unknown():usage;
        for(int attempt=0;attempt<3;attempt++) {
            try {transaction.executeWithoutResult(tx->save(callId,outcome,actual,safe(requestId),safe(errorCode)));return true;}
            catch(TransientDataAccessException | RecoverableDataAccessException | CannotCreateTransactionException e) {
                if(attempt==2){log.warn("AI用量落账未完成，callId={}，保留待核对",callId);return false;}
                try {Thread.sleep(50L*(attempt+1));}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();return false;}
            }
            catch(DataAccessException e) {log.warn("AI用量落账失败，callId={}，请核对数据库记录",callId);return false;}
        }
        return false;
    }

    private void save(String id,Outcome outcome,AiUsage usage,String requestId,String errorCode) {
        var row=required(mapper.lock(id));
        boolean rawValid=true;String raw=null;
        if(usage.rawJson()!=null) {
            try {
                if(usage.rawJson().length()>32768) throw new IllegalArgumentException();
                var tree=json.readTree(usage.rawJson());
                if(tree==null || !tree.isObject()) throw new IllegalArgumentException();
                raw=json.writeValueAsString(tree);
            } catch(Exception invalid){rawValid=false;}
        }
        var cost=rawValid?calculator.calculate(row.getPriceSnapshot(),usage):new AiCostCalculator.Cost(null,"INVALID_USAGE");
        if(outcome==Outcome.NOT_SENT) {
            if(usage.inputTokens()!=null || usage.outputTokens()!=null || usage.audioSeconds()!=null || raw!=null || requestId!=null
                    || row.getRemoteTaskId()!=null)
                throw new IllegalArgumentException("已收到用量或回执不能标未发送");
            cost=new AiCostCalculator.Cost(java.math.BigDecimal.ZERO.setScale(10),null);
        }
        if(!row.getStatus().equals("RUNNING") && !row.getStatus().equals("UNKNOWN") && !row.getStatus().equals(outcome.name()))
            throw new IllegalStateException("调用终态冲突");
        if(row.getRequestId()!=null && requestId!=null && !row.getRequestId().equals(requestId)) throw new IllegalStateException("请求ID冲突");
        // 已报告的数字只允许重复或补空，不能更改；空回执不抹掉历史消耗。
        compatible(row.getInputTokens(),usage.inputTokens());compatible(row.getOutputTokens(),usage.outputTokens());
        if(row.getAudioSeconds()!=null && usage.audioSeconds()!=null && row.getAudioSeconds().compareTo(usage.audioSeconds())!=0)
            throw new IllegalStateException("音频用量冲突");
        if(row.getEstimatedCostCny()!=null) {
            if(cost.amount()!=null && row.getEstimatedCostCny().compareTo(cost.amount())!=0) throw new IllegalStateException("费用回执冲突");
            if(row.getStatus().equals("UNKNOWN") && outcome!=Outcome.UNKNOWN) {
                row.setStatus(outcome.name());if(requestId!=null)row.setRequestId(requestId);
                row.setErrorCode(errorCode);row.setFinishedAt(LocalDateTime.now());mapper.finish(row);
            }
            return;
        }
        // 拒绝部分回执覆盖已经保存的较完整回执；需要补充时合并已有数字再核算。
        Long input=usage.inputTokens()!=null?usage.inputTokens():row.getInputTokens();
        Long output=usage.outputTokens()!=null?usage.outputTokens():row.getOutputTokens();
        var seconds=usage.audioSeconds()!=null?usage.audioSeconds():row.getAudioSeconds();
        if(outcome!=Outcome.NOT_SENT && rawValid) cost=calculator.calculate(row.getPriceSnapshot(),new AiUsage(input,output,seconds,raw));
        if(!"INVALID_USAGE".equals(cost.unknownReason())) {
            row.setInputTokens(input);row.setOutputTokens(output);row.setAudioSeconds(seconds);
        }
        if(raw!=null)row.setUsageJson(raw);
        row.setEstimatedCostCny(cost.amount());row.setCostUnknownReason(cost.unknownReason());
        row.setStatus(outcome.name());if(requestId!=null)row.setRequestId(requestId);
        row.setErrorCode(errorCode);row.setFinishedAt(LocalDateTime.now());mapper.finish(row);
    }
    private static void compatible(Long old,Long next){if(old!=null && next!=null && !old.equals(next))throw new IllegalStateException("Token用量冲突");}
    private static void sameOwner(AiCallLog row,AiCallContext context,String model) {
        if(!row.getTaskId().equals(context.taskId()) || row.getExecutionNo()!=context.executionNo()
                || !row.getStage().equals(context.stage().name()) || row.getSubtaskNo()!=context.subtaskNo() || !row.getModel().equals(model))
            throw new IllegalStateException("callId已属于其他调用");
    }
    private static AiCallLog required(AiCallLog row){if(row==null)throw new IllegalArgumentException("调用尚未登记");return row;}
    private static void uuid(String id){if(id==null || !UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException("无效callId");}
    private static String safe(String value){return value!=null && value.matches("[A-Za-z0-9_.-]{1,128}")?value:null;}
}
