package com.videoai.worker.ownership;
import com.videoai.common.analysis.ExecutionOwnership;
import com.videoai.infra.mysql.mapper.AnalysisTaskMapper;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.mybatis.spring.*;
import javax.sql.DataSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class TaskWriteFenceTest {
 @Configuration @EnableAspectJAutoProxy(proxyTargetClass=true) @org.springframework.transaction.annotation.EnableTransactionManagement static class Aop {}
 AnnotationConfigApplicationContext context;JdbcTemplate jdbc;TaskLeaseService leases;AnalysisTaskMapper mapper;
 @BeforeEach void setup() throws Exception {
  var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
  jdbc=new JdbcTemplate(ds);
  jdbc.execute("CREATE TABLE analysis_task(task_id VARCHAR(64) PRIMARY KEY,retry_count INT,status VARCHAR(32),progress INT,error_message TEXT,completed_at TIMESTAMP,updated_at TIMESTAMP,execution_owner VARCHAR(64),execution_lease_until TIMESTAMP,execution_heartbeat_at TIMESTAMP)");
  jdbc.execute("CREATE TABLE analysis_segment(task_id VARCHAR(64),execution_no INT,segment_no INT,status VARCHAR(32),error_message TEXT,completed_at TIMESTAMP)");
  jdbc.update("INSERT INTO analysis_task(task_id,retry_count,status,progress) VALUES ('task',0,'PROCESSING',0)");
  var factory=new SqlSessionFactoryBean();factory.setDataSource(ds);var cfg=new org.apache.ibatis.session.Configuration();cfg.addMapper(AnalysisTaskMapper.class);cfg.addMapper(com.videoai.infra.mysql.mapper.AnalysisSegmentMapper.class);factory.setConfiguration(cfg);
  var template=new SqlSessionTemplate(factory.getObject());
  leases=new TaskLeaseService(jdbc,new DataSourceTransactionManager(ds));
  context=new AnnotationConfigApplicationContext();
  context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("test",Map.of("videoai.worker.async-enabled","true")));
  context.register(Aop.class);
  context.registerBean("leases",TaskLeaseService.class,()->leases);
  context.registerBean("fence",TaskWriteFence.class,()->new TaskWriteFence(leases));
  context.registerBean("analysisTaskMapper",org.mybatis.spring.mapper.MapperFactoryBean.class,()->{
   var bean=new org.mybatis.spring.mapper.MapperFactoryBean<>(AnalysisTaskMapper.class);bean.setSqlSessionTemplate(template);return bean;
  });
  context.registerBean("analysisSegmentMapper",org.mybatis.spring.mapper.MapperFactoryBean.class,()->{
   var bean=new org.mybatis.spring.mapper.MapperFactoryBean<>(com.videoai.infra.mysql.mapper.AnalysisSegmentMapper.class);bean.setSqlSessionTemplate(template);return bean;
  });
  context.registerBean("transactionManager",DataSourceTransactionManager.class,()->new DataSourceTransactionManager(ds));
  context.registerBean(com.videoai.worker.service.TaskFailureService.class);context.refresh();
  mapper=context.getBean(AnalysisTaskMapper.class);
  assertTrue(context.containsBean("fence"));
  assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(mapper),"Mapper必须实际经过执行权切面");
  assertFalse(org.springframework.aop.support.AopUtils.isAopProxy(context.getBean("&analysisTaskMapper")),"MapperFactoryBean不能被切面代理");
 }
 @AfterEach void close(){context.close();}
 @Test void expiredOwnerCannotWriteAndNewOwnerCan() throws Exception {
  var old=leases.claim("task",0);assertNotNull(old);assertNull(leases.claim("task",0));
  try(var scope=ExecutionOwnership.bind(old)){assertEquals(1,mapper.updateProgress("task",0,10));}
  jdbc.update("UPDATE analysis_task SET execution_lease_until=TIMESTAMPADD(SECOND,-1,NOW())");
  var replacement=leases.claim("task",0);assertNotNull(replacement);
  try(var scope=ExecutionOwnership.bind(old)){assertThrows(Exception.class,()->mapper.updateProgress("task",0,99));}
  assertEquals(10,jdbc.queryForObject("SELECT progress FROM analysis_task",Integer.class));
  leases.release(old); // 旧执行收尾不能清掉新所有者的租约。
  try(var scope=ExecutionOwnership.bind(replacement)){assertEquals(1,mapper.updateProgress("task",0,20));}
 }
 @Test void missingContextCancellationAndRetryCannotWrite() throws Exception {
  assertThrows(Exception.class,()->mapper.updateProgress("task",0,1));
  var token=leases.claim("task",0);
  jdbc.update("UPDATE analysis_task SET status='CANCELLED'");
  try(var scope=ExecutionOwnership.bind(token)){assertThrows(Exception.class,()->mapper.updateProgress("task",0,2));}
  jdbc.update("UPDATE analysis_task SET status='PROCESSING',retry_count=1");
  try(var scope=ExecutionOwnership.bind(token)){assertThrows(Exception.class,()->mapper.updateProgress("task",0,3));}
 }
 @Test void fencedActionRollsBackAndRenewedLeaseRemainsValid() {
  var token=leases.claim("task",0);leases.renew(token);assertTrue(token.valid());
  assertThrows(Exception.class,()->leases.fenced(token,()->{jdbc.update("UPDATE analysis_task SET progress=90");throw new IllegalStateException("failure");}));
  assertEquals(0,jdbc.queryForObject("SELECT progress FROM analysis_task",Integer.class));leases.release(token);assertFalse(token.valid());
 }
 @Test void tokenCannotWriteAnotherTaskAndSubtableUsesTheSameFence() throws Exception {
  var token=leases.claim("task",0);
  jdbc.update("INSERT INTO analysis_task(task_id,retry_count,status,progress) VALUES ('other',0,'PROCESSING',0)");
  jdbc.update("INSERT INTO analysis_segment(task_id,execution_no,segment_no,status) VALUES ('task',0,0,'PREPARED')");
  var segments=context.getBean(com.videoai.infra.mysql.mapper.AnalysisSegmentMapper.class);
  try(var scope=ExecutionOwnership.bind(token)){
   assertThrows(Exception.class,()->mapper.updateProgress("other",0,100));
   assertEquals(1,segments.markProcessing("task",0,0));
   token.invalidate();
   assertThrows(Exception.class,()->segments.markProcessing("task",0,0));
  }
 }
 @Test void competingClaimsHaveOneWinner() throws Exception {
  var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
  try {
   var start=new java.util.concurrent.CountDownLatch(1);
   var a=pool.submit(()->{start.await();return leases.claim("task",0);});
   var b=pool.submit(()->{start.await();return leases.claim("task",0);});start.countDown();
   assertEquals(1,(a.get()==null?0:1)+(b.get()==null?0:1));
  } finally {pool.shutdownNow();}
 }
 @Test void partialFailureClosesUnfinishedSegmentsAndPreservesSuccess() throws Exception {
  jdbc.update("INSERT INTO analysis_segment(task_id,execution_no,segment_no,status) VALUES ('task',0,0,'SUCCEEDED'),('task',0,1,'PROCESSING'),('task',0,2,'PREPARED')");
  var token=leases.claim("task",0);
  try(var scope=ExecutionOwnership.bind(token)){
   assertTrue(context.getBean(com.videoai.worker.service.TaskFailureService.class).markExecutionFailed("task",0,"one failed"));
  }
  assertEquals("PARTIALLY_COMPLETED",jdbc.queryForObject("SELECT status FROM analysis_task",String.class));
  assertEquals(List.of("SUCCEEDED","FAILED","FAILED"),jdbc.queryForList("SELECT status FROM analysis_segment ORDER BY segment_no",String.class));
  assertTrue(leases.settled("task",0));
 }
}
