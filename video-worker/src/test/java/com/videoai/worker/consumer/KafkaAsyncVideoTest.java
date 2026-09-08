package com.videoai.worker.consumer;
import com.videoai.common.message.TaskMessage;
import com.videoai.common.analysis.ExecutionOwnership;
import com.videoai.worker.processor.TaskProcessor;
import com.videoai.worker.ownership.TaskLeaseService;
import org.junit.jupiter.api.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.*;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.*;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import org.springframework.kafka.test.utils.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
@Timeout(60)
class KafkaAsyncVideoTest {
 @Test void continuesPollingWhilePausedAndCommitsOnlyAfterPersistence() throws Exception {
  String topic="async-video",group="async-"+UUID.randomUUID();var broker=new EmbeddedKafkaKraftBroker(1,2,topic);broker.afterPropertiesSet();
  var allow=new CountDownLatch(1);var entered=new CountDownLatch(1);var firstDone=new AtomicBoolean();var calls=new AtomicInteger();var assignments=new AtomicInteger();
  var leases=mock(TaskLeaseService.class);var processor=mock(TaskProcessor.class);
  when(leases.settled(anyString(),eq(0))).thenAnswer(i->firstDone.get());
  when(leases.claim(anyString(),eq(0))).thenAnswer(i->new ExecutionOwnership.Token(i.getArgument(0),0,"owner",System.nanoTime()+TimeUnit.SECONDS.toNanos(30)));
  when(processor.process(any())).thenAnswer(i->{calls.incrementAndGet();assertNotNull(ExecutionOwnership.current());entered.countDown();assertTrue(allow.await(15,TimeUnit.SECONDS));firstDone.set(true);return true;});
  try(var coordinator=new AsyncVideoCoordinator(processor,leases)) {
   var props=KafkaTestUtils.consumerProps(group,"false",broker);props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,"earliest");props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG,1);props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG,1000);
   var cp=new ContainerProperties(topic);cp.setAckMode(ContainerProperties.AckMode.MANUAL);cp.setAsyncAcks(true);cp.setPollTimeout(100);
   cp.setConsumerRebalanceListener(new ConsumerAwareRebalanceListener(){
    public void onPartitionsAssigned(Consumer<?,?> c,Collection<TopicPartition> p){assignments.incrementAndGet();}
    public void onPartitionsRevokedBeforeCommit(Consumer<?,?> c,Collection<TopicPartition> p){coordinator.revoke(c,p);}
   });
   cp.setMessageListener((AcknowledgingConsumerAwareMessageListener<Integer,String>)(record,ack,consumer)->coordinator.accept(consumer,new TopicPartition(topic,record.partition()),TaskMessage.builder().taskId(record.value()).businessRetryNo(0).build(),ack));
   var container=new KafkaMessageListenerContainer<>(new DefaultKafkaConsumerFactory<Integer,String>(props),cp);
   try(var producer=new KafkaProducer<Integer,String>(KafkaTestUtils.producerProps(broker),new IntegerSerializer(),new StringSerializer());var observer=new KafkaConsumer<Integer,String>(props)) {
    container.start();ContainerTestUtils.waitForAssignment(container,2);
    producer.send(new ProducerRecord<>(topic,0,1,"task")).get();assertTrue(entered.await(10,TimeUnit.SECONDS));
    producer.send(new ProducerRecord<>(topic,1,2,"later")).get();
    Thread.sleep(2500);assertEquals(1,calls.get());assertEquals(1,assignments.get());
    var lastPoll=container.metrics().values().stream().flatMap(m->m.entrySet().stream())
        .filter(e->e.getKey().name().equals("last-poll-seconds-ago")).findFirst().orElseThrow();
    assertTrue(((Number)lastPoll.getValue().metricValue()).doubleValue()<1,"等待结果时仍须持续poll");
    var before=observer.committed(Set.of(new TopicPartition(topic,0))).get(new TopicPartition(topic,0));assertTrue(before==null||before.offset()==0);
    allow.countDown();long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
    boolean committed=false;
    while(System.nanoTime()<until){var offsets=observer.committed(Set.of(new TopicPartition(topic,0),new TopicPartition(topic,1)));committed=offsets.values().stream().allMatch(v->v!=null&&v.offset()==1);if(committed)break;Thread.sleep(50);}
    assertTrue(committed);assertEquals(1,calls.get());
   } finally {allow.countDown();container.stop();}
  } finally {broker.destroy();}
 }
 @Test void revokedExecutionCannotAcknowledgeLateCompletion() throws Exception {
  var leases=mock(TaskLeaseService.class);var processor=mock(TaskProcessor.class);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var done=new CountDownLatch(1);var terminal=new AtomicBoolean();
  when(leases.settled("task",0)).thenAnswer(i->terminal.get());
  var token=new ExecutionOwnership.Token("task",0,"old",System.nanoTime()+TimeUnit.SECONDS.toNanos(30));when(leases.claim("task",0)).thenReturn(token);
  when(processor.process(any())).thenAnswer(i->{entered.countDown();while(release.getCount()>0){try{release.await();}catch(InterruptedException ignored){}}terminal.set(true);done.countDown();return true;});
  var consumer=mock(Consumer.class);var ack=mock(org.springframework.kafka.support.Acknowledgment.class);var partition=new TopicPartition("test",0);
  try(var coordinator=new AsyncVideoCoordinator(processor,leases)) {
   coordinator.accept(consumer,partition,TaskMessage.builder().taskId("task").businessRetryNo(0).build(),ack);
   assertTrue(entered.await(3,TimeUnit.SECONDS));coordinator.revoke(consumer,List.of(partition));assertFalse(token.valid());release.countDown();assertTrue(done.await(3,TimeUnit.SECONDS));Thread.sleep(100);verifyNoInteractions(ack);
  } finally {release.countDown();}
 }

 @Test void actualRebalanceRedeliversUncommittedWorkAndLateOldCompletionCannotSkipNextRecord() throws Exception {
  String topic="rebalance-video",group="rebalance-"+UUID.randomUUID();
  var broker=new EmbeddedKafkaKraftBroker(1,1,topic);broker.afterPropertiesSet();
  var entered=new CountDownLatch(1);var oldRelease=new CountDownLatch(1);var calls=new AtomicInteger();
  var assignments=new AtomicInteger();var terminal=ConcurrentHashMap.<String>newKeySet();
  var oldToken=new AtomicReference<ExecutionOwnership.Token>();
  var leases=mock(TaskLeaseService.class);var processor=mock(TaskProcessor.class);
  when(leases.settled(anyString(),eq(0))).thenAnswer(i->terminal.contains(i.<String>getArgument(0)));
  when(leases.claim(anyString(),eq(0))).thenAnswer(i->new ExecutionOwnership.Token(i.getArgument(0),0,UUID.randomUUID().toString(),System.nanoTime()+TimeUnit.SECONDS.toNanos(30)));
  when(processor.process(any())).thenAnswer(i->{
   if(calls.incrementAndGet()==1){
    oldToken.set(ExecutionOwnership.current());entered.countDown();
    while(oldRelease.getCount()>0)try{oldRelease.await();}catch(InterruptedException ignored){}
   }
   terminal.add(i.<TaskMessage>getArgument(0).getTaskId());return true;
  });
  try(var coordinator=new AsyncVideoCoordinator(processor,leases)) {
   var props=KafkaTestUtils.consumerProps(group,"false",broker);
   props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,"earliest");props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG,1);
   props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG,10000);
   var cp=new ContainerProperties(topic);cp.setAckMode(ContainerProperties.AckMode.MANUAL);cp.setAsyncAcks(true);cp.setPollTimeout(100);
   cp.setConsumerRebalanceListener(new ConsumerAwareRebalanceListener(){
    public void onPartitionsAssigned(Consumer<?,?> c,Collection<TopicPartition> p){assignments.incrementAndGet();}
    public void onPartitionsRevokedBeforeCommit(Consumer<?,?> c,Collection<TopicPartition> p){coordinator.revoke(c,p);}
   });
   cp.setMessageListener((AcknowledgingConsumerAwareMessageListener<Integer,String>)(r,ack,c)->coordinator.accept(c,new TopicPartition(topic,r.partition()),TaskMessage.builder().taskId(r.value()).businessRetryNo(0).build(),ack));
   var container=new KafkaMessageListenerContainer<>(new DefaultKafkaConsumerFactory<Integer,String>(props),cp);
   try(var producer=new KafkaProducer<Integer,String>(KafkaTestUtils.producerProps(broker),new IntegerSerializer(),new StringSerializer());var observer=new KafkaConsumer<Integer,String>(props)) {
    container.start();ContainerTestUtils.waitForAssignment(container,1);
    producer.send(new ProducerRecord<>(topic,0,1,"first")).get();assertTrue(entered.await(10,TimeUnit.SECONDS));
    container.enforceRebalance();
    long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
    while(System.nanoTime()<until && !terminal.contains("first"))Thread.sleep(50);
    assertTrue(assignments.get()>=2);assertTrue(terminal.contains("first"),"撤销未ACK记录后应重投并完成");
    assertFalse(oldToken.get().valid());assertEquals(2,calls.get());
    oldRelease.countDown();
    producer.send(new ProducerRecord<>(topic,0,2,"second")).get();
    until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);boolean committed=false;
    while(System.nanoTime()<until){var offset=observer.committed(Set.of(new TopicPartition(topic,0))).get(new TopicPartition(topic,0));
     committed=offset!=null&&offset.offset()==2;if(committed)break;Thread.sleep(50);}
    assertTrue(committed);assertTrue(terminal.contains("second"));assertEquals(3,calls.get());
   } finally {oldRelease.countDown();container.stop();}
  } finally {broker.destroy();}
 }
}
