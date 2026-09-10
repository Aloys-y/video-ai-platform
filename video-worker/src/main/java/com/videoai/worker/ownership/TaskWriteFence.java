package com.videoai.worker.ownership;
import com.videoai.common.analysis.ExecutionOwnership;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Aspect @Component @RequiredArgsConstructor
public class TaskWriteFence {
 private final com.videoai.worker.scheduler.TaskDispatchRepository leases;
 private final java.util.concurrent.atomic.LongAdder rejectedWrites = new java.util.concurrent.atomic.LongAdder();
 public long rejectedWrites(){return rejectedWrites.sum();}
 // 只拦截Mapper产品，不能代理MapperFactoryBean本身，否则类型探测会触发切面循环创建。
 @Around("execution(* com.videoai.infra.mysql.mapper.*.*(..)) || execution(* com.baomidou.mybatisplus.core.mapper.BaseMapper.*(..))")
 public Object guard(ProceedingJoinPoint point) throws Throwable {
  String name=point.getSignature().getName();
  if(name.startsWith("select") || name.startsWith("is") || name.startsWith("get") || name.startsWith("count"))return point.proceed();
  var token=ExecutionOwnership.current();
  Object mapper=point.getThis();
   boolean protectedMapper=mapper instanceof com.videoai.infra.mysql.mapper.AnalysisTaskMapper
       || mapper instanceof com.videoai.infra.mysql.mapper.AnalysisExecutionMapper
       || mapper instanceof com.videoai.infra.mysql.mapper.AnalysisSegmentMapper
       || mapper instanceof com.videoai.infra.mysql.mapper.AnalysisAsrPartMapper
       || mapper instanceof com.videoai.infra.mysql.mapper.AnalysisTextCallMapper;
  if(token==null) {
   if(protectedMapper){rejectedWrites.increment();throw new IllegalStateException("视频写入缺少执行令牌");}
   return point.proceed(); // 独立知识索引不属于视频执行写入。
  }
  if(protectedMapper) verifyTarget(point.getArgs(),token);
  if(!protectedMapper)return point.proceed();
  token.check();
  return leases.fenced(new com.videoai.worker.scheduler.TaskDispatchRepository.Lease(token.taskId,token.executionNo,token.owner),()->{try{token.check();return point.proceed();}catch(RuntimeException e){throw e;}catch(Throwable e){throw new IllegalStateException("Mapper写入失败",e);}});
 }
 private void verifyTarget(Object[] args,ExecutionOwnership.Token token) {
  String taskId=null;Integer executionNo=null;
  Object first=args.length==0?null:args[0];
  if(first instanceof String id){taskId=id;if(args.length>1 && args[1] instanceof Integer no)executionNo=no;}
  else if(first instanceof com.videoai.common.domain.AnalysisExecution row){taskId=row.getTaskId();executionNo=row.getExecutionNo();}
  else if(first instanceof com.videoai.common.domain.AnalysisSegment row){taskId=row.getTaskId();executionNo=row.getExecutionNo();}
  else if(first instanceof com.videoai.common.domain.AnalysisAsrPart row){taskId=row.getTaskId();executionNo=row.getExecutionNo();}
  else if(first instanceof com.videoai.common.domain.AnalysisTextCall row){taskId=row.getTaskId();executionNo=row.getExecutionNo();}
  // Worker禁止使用无法识别任务范围的通用Wrapper写入。
  if(!token.taskId.equals(taskId) || executionNo==null || executionNo!=token.executionNo){
   rejectedWrites.increment();throw new IllegalStateException("写入目标与执行令牌不匹配");
  }
 }
}
