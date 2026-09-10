package com.videoai.common.enums;
import lombok.AllArgsConstructor;
import lombok.Getter;
/** 数据库任务业务状态，不包含消息队列状态。 */
@Getter @AllArgsConstructor
public enum TaskStatus {
 PENDING("PENDING","待处理",0), RUNNING("RUNNING","处理中",1),
 SUCCEEDED("SUCCEEDED","已完成",2), PARTIAL("PARTIAL","部分完成",3),
 FAILED("FAILED","失败",4), CANCELLED("CANCELLED","已取消",5);
 private final String code; private final String description; private final int order;
 public boolean isFinalState(){return this!=PENDING && this!=RUNNING;}
 public boolean canTransitionTo(TaskStatus target){
  if(this==target)return true;
  return switch(this){
   case PENDING -> target==RUNNING || target==CANCELLED;
   case RUNNING -> target==SUCCEEDED || target==PARTIAL || target==FAILED || target==CANCELLED;
   case FAILED,PARTIAL -> target==PENDING || target==CANCELLED;
   case SUCCEEDED,CANCELLED -> false;
  };
 }
 public static TaskStatus fromCode(String code){return code==null?null:TaskStatus.valueOf(code);}
}
