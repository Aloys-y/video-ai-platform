package com.videoai.worker.consumer;
import com.videoai.common.analysis.ExecutionOwnership;
import com.videoai.common.message.TaskMessage;
import com.videoai.worker.ownership.TaskLeaseService;
import com.videoai.worker.processor.TaskProcessor;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** 一个消费者最多一个在途记录，asyncAcks管理暂停；工作线程绝不操作KafkaConsumer。 */
@Component @Slf4j
public class AsyncVideoCoordinator implements AutoCloseable {
 private final TaskProcessor processor;private final TaskLeaseService leases;
 private final ConcurrentMap<Consumer<?,?>,Slot> slots=new ConcurrentHashMap<>();
 private final Set<Slot> running=ConcurrentHashMap.newKeySet();
 private final ThreadPoolExecutor videos;
 private final ScheduledExecutorService maintenance;
 @org.springframework.beans.factory.annotation.Value("${videoai.worker.task-timeout-minutes:30}")
 private int slowTaskMinutes=30;
 private final LongAdder rejectedSubmissions=new LongAdder();
 private final LongAdder revokedExecutions=new LongAdder();
 private volatile boolean closed;
 public AsyncVideoCoordinator(TaskProcessor processor,TaskLeaseService leases) {
  this.processor=processor;this.leases=leases;
  videos=new ThreadPoolExecutor(3,3,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(3),r->{var t=new Thread(r,"video-execution");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
  maintenance=Executors.newScheduledThreadPool(2,r->{var t=new Thread(r,"video-lease-maintenance");t.setDaemon(true);return t;});
  maintenance.scheduleWithFixedDelay(this::dispatchPending,1,1,TimeUnit.SECONDS);
  maintenance.scheduleWithFixedDelay(this::renew,20,20,TimeUnit.SECONDS);
  maintenance.scheduleWithFixedDelay(this::reportHealth,60,60,TimeUnit.SECONDS);
 }
 private static final class Slot {
  final Consumer<?,?> consumer;final TopicPartition partition;final TaskMessage message;final Acknowledgment ack;
  final long acceptedAt=System.nanoTime();volatile long nextFailureLog;volatile boolean slowReported;
  final AtomicBoolean submitted=new AtomicBoolean();volatile boolean revoked;volatile boolean ackRequested;volatile Thread runner;volatile ExecutionOwnership.Token token;
  Slot(Consumer<?,?> c,TopicPartition p,TaskMessage m,Acknowledgment a){consumer=c;partition=p;message=m;ack=a;}
  synchronized void revoke(){revoked=true;if(token!=null)token.invalidate();if(runner!=null)runner.interrupt();}
  synchronized void acknowledge(){
   if(!revoked){
    // 容器可在ack调用返回前交付下一条；先开放交接，旧线程随后只释放自己的租约。
    ackRequested=true;
    try{ack.acknowledge();}catch(RuntimeException e){ackRequested=false;throw e;}
   }
  }
 }
 public void accept(Consumer<?,?> consumer,TopicPartition partition,TaskMessage message,Acknowledgment ack) {
  if(closed)throw new IllegalStateException("视频执行器已关闭");
  var slot=new Slot(consumer,partition,message,ack);
  slots.compute(consumer,(key,previous)->{
   if(previous!=null && !previous.ackRequested)throw new IllegalStateException("消费者存在未确认任务，检查asyncAcks和max.poll.records");
   return slot;
  });
  dispatch(slot);
 }
 private void dispatch(Slot slot) {
  if(closed||slot.revoked||!slot.submitted.compareAndSet(false,true))return;
  try{videos.execute(()->run(slot));}
  catch(RejectedExecutionException e){rejectedSubmissions.increment();slot.submitted.set(false);} // 保留有界在途记录，维护线程重交，不ACK。
 }
 private void dispatchPending(){if(!closed)for(var slot:slots.values())dispatch(slot);}
 private void renew(){for(var slot:running) {var token=slot.token;if(token!=null && token.valid())leases.renew(token);}}
 public void revoke(Consumer<?,?> consumer,Collection<TopicPartition> partitions) {
  var slot=slots.get(consumer);
  if(slot!=null && partitions.contains(slot.partition)){revokedExecutions.increment();slot.revoke();slots.remove(consumer,slot);}
 }
 private void run(Slot slot) {
  slot.runner=Thread.currentThread();running.add(slot);
  int no=slot.message.getBusinessRetryNo()==null?0:slot.message.getBusinessRetryNo();
  try {
   while(!closed && !slot.revoked) {
    try {
     if(leases.settled(slot.message.getTaskId(),no)){slot.acknowledge();return;}
     var token=leases.claim(slot.message.getTaskId(),no);
     if(token==null){Thread.sleep(1000);continue;}
     slot.token=token;
     try(var ownership=ExecutionOwnership.bind(token)) {
      if(slot.revoked){token.invalidate();return;}
      if(processor.process(slot.message) && leases.settled(slot.message.getTaskId(),no)){slot.acknowledge();return;}
     } finally {slot.token=null;try{leases.release(token);}catch(Exception e){log.warn("租约释放失败，将等待到期: taskId={}",slot.message.getTaskId());}}
    } catch(InterruptedException e){Thread.currentThread().interrupt();return;}
    catch(Exception e) {
     // 异步异常不会进入监听器ErrorHandler；不ACK，按持久化状态恢复，不凭异常直接重发付费请求。
     if(System.nanoTime()>=slot.nextFailureLog){
      slot.nextFailureLog=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
      log.warn("异步任务未收敛，保留消息并核对状态: taskId={}, type={}",slot.message.getTaskId(),e.getClass().getSimpleName());
     }
     try{Thread.sleep(1000);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();return;}
    }
   }
  } finally {
   running.remove(slot);slot.runner=null;
   if(!closed && !slot.revoked && !slot.ackRequested)slot.submitted.set(false);
   else slots.remove(slot.consumer,slot);
  }
 }
 public record Stats(int inFlight,int running,int queued,long rejectedSubmissions,long revokedExecutions,long renewalFailures){}
 public Stats stats(){return new Stats(slots.size(),videos.getActiveCount(),videos.getQueue().size(),rejectedSubmissions.sum(),revokedExecutions.sum(),leases.renewalFailures());}
 public void revokeAll(){
  slots.forEach((consumer,slot)->{slot.revoke();slots.remove(consumer,slot);});
  running.forEach(Slot::revoke);
 }
 private void reportHealth(){
  if(slots.isEmpty() && running.isEmpty())return;
  log.info("异步视频消费状态: {}",stats());
  for(var slot:slots.values()) {
   if(!slot.slowReported && System.nanoTime()-slot.acceptedAt>=TimeUnit.MINUTES.toNanos(slowTaskMinutes)){
    slot.slowReported=true;
    log.warn("视频处理超过耗时提示阈值，继续等待且不强制失败: taskId={}, minutes={}",slot.message.getTaskId(),slowTaskMinutes);
   }
  }
 }
 @Override @PreDestroy public void close(){closed=true;slots.values().forEach(Slot::revoke);running.forEach(Slot::revoke);videos.shutdownNow();maintenance.shutdownNow();}
}
