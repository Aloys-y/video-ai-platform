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
 AnnotationConfigApplicationContext context;JdbcTemplate jdbc;com.videoai.worker.scheduler.TaskDispatchRepository leases;AnalysisTaskMapper mapper;
 @BeforeEach void setup() throws Exception {
  var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
  jdbc=new JdbcTemplate(ds);
  jdbc.execute("CREATE TABLE analysis_task(task_id VARCHAR(64) PRIMARY KEY,attempt_no INT,status VARCHAR(32),progress INT,error_message TEXT,error_code VARCHAR(64),created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,started_at TIMESTAMP,finished_at TIMESTAMP,updated_at TIMESTAMP,owner_token VARCHAR(64),lease_until TIMESTAMP,heartbeat_at TIMESTAMP)");
  jdbc.execute("CREATE TABLE analysis_segment(task_id VARCHAR(64),execution_no INT,segment_no INT,status VARCHAR(32),error_message TEXT,completed_at TIMESTAMP)");
  jdbc.update("INSERT INTO analysis_task(task_id,attempt_no,status,progress) VALUES ('task',0,'PENDING',0)");
  var factory=new SqlSessionFactoryBean();factory.setDataSource(ds);var cfg=new org.apache.ibatis.session.Configuration();cfg.addMapper(AnalysisTaskMapper.class);cfg.addMapper(com.videoai.infra.mysql.mapper.AnalysisSegmentMapper.class);factory.setConfiguration(cfg);
  var template=new SqlSessionTemplate(factory.getObject());
  leases=new com.videoai.worker.scheduler.TaskDispatchRepository(ds,new DataSourceTransactionManager(ds),90);
  context=new AnnotationConfigApplicationContext();
  context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("test",Map.of("videoai.worker.async-enabled","true")));
  context.register(Aop.class);
  context.registerBean("leases",com.videoai.worker.scheduler.TaskDispatchRepository.class,()->leases);
  context.registerBean("fence",TaskWriteFence.class,()->new TaskWriteFence(leases));
  context.registerBean("analysisTaskMapper",org.mybatis.spring.mapper.MapperFactoryBean.class,()->{
   var bean=new org.mybatis.spring.mapper.MapperFactoryBean<>(AnalysisTaskMapper.class);bean.setSqlSessionTemplate(template);return bean;
  });
  context.registerBean("analysisSegmentMapper",org.mybatis.spring.mapper.MapperFactoryBean.class,()->{
   var bean=new org.mybatis.spring.mapper.MapperFactoryBean<>(com.videoai.infra.mysql.mapper.AnalysisSegmentMapper.class);bean.setSqlSessionTemplate(template);return bean;
  });
  context.registerBean("transactionManager",DataSourceTransactionManager.class,()->new DataSourceTransactionManager(ds));
  context.refresh();
  mapper=context.getBean(AnalysisTaskMapper.class);
  assertTrue(context.containsBean("fence"));
  assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(mapper),"Mapper必须实际经过执行权切面");
  assertFalse(org.springframework.aop.support.AopUtils.isAopProxy(context.getBean("&analysisTaskMapper")),"MapperFactoryBean不能被切面代理");
 }
 @AfterEach void close(){context.close();}

 ExecutionOwnership.Token token(com.videoai.worker.scheduler.TaskDispatchRepository.Lease lease){
  return new ExecutionOwnership.Token(lease.taskId(),lease.attemptNo(),lease.owner(),Long.MAX_VALUE);
 }
 @Test void mapperUsesDatabaseOwnerFence() throws Exception {
  var first=leases.claim(new com.videoai.worker.scheduler.TaskDispatchRepository.Candidate("task",0));
  try(var bound=ExecutionOwnership.bind(token(first))){assertEquals(1,mapper.updateProgress("task",0,10));}
  jdbc.update("UPDATE analysis_task SET owner_token='replacement'");
  try(var bound=ExecutionOwnership.bind(token(first))){assertThrows(Exception.class,()->mapper.updateProgress("task",0,99));}
  assertEquals(10,jdbc.queryForObject("SELECT progress FROM analysis_task",Integer.class));
 }
 @Test void cancellationAndMissingTokenBlockWrites() throws Exception {
  assertThrows(Exception.class,()->mapper.updateProgress("task",0,1));
  var lease=leases.claim(new com.videoai.worker.scheduler.TaskDispatchRepository.Candidate("task",0));
  jdbc.update("UPDATE analysis_task SET status='CANCELLED'");
  try(var bound=ExecutionOwnership.bind(token(lease))){assertThrows(Exception.class,()->mapper.updateProgress("task",0,1));}
 }
 @Test void subtableWriteChecksParentAndTaskIdentity() throws Exception {
  var lease=leases.claim(new com.videoai.worker.scheduler.TaskDispatchRepository.Candidate("task",0));
  jdbc.update("INSERT INTO analysis_segment(task_id,execution_no,segment_no,status) VALUES ('task',0,0,'PREPARED')");
  var segments=context.getBean(com.videoai.infra.mysql.mapper.AnalysisSegmentMapper.class);
  try(var bound=ExecutionOwnership.bind(token(lease))){
   assertThrows(Exception.class,()->mapper.updateProgress("other",0,50));
   assertEquals(1,segments.markProcessing("task",0,0));
   jdbc.update("UPDATE analysis_task SET lease_until=TIMESTAMPADD(SECOND,-1,NOW())");
   assertThrows(Exception.class,()->segments.failUnfinishedRunning("task",0));
  }
 }
}
