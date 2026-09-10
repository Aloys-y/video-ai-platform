package com.videoai.worker.scheduler;

import com.fasterxml.jackson.databind.*;
import com.videoai.common.analysis.*;
import com.videoai.infra.minio.service.StorageService;
import com.videoai.worker.VideoWorkerApplication;
import com.videoai.worker.asr.CloudAsrClient;
import com.videoai.worker.service.provider.AiVideoProvider;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 显式授权的付费验收。保留随机库与账本，失败后不自动再次提交视频。 */
@EnabledIfSystemProperty(named="dispatch.live.acceptance", matches="true")
@Timeout(value=50, unit=TimeUnit.MINUTES)
class DatabaseLiveVideoAcceptanceTest {
    static final Path ROOT=Path.of("..").toAbsolutePath().normalize();
    static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    static Path output;
    static String database;

    static StandardEnvironment credentials() throws Exception {
        var env=new StandardEnvironment();
        for(var source:new YamlPropertySourceLoader().load("dev",new FileSystemResource(ROOT.resolve("video-worker/src/main/resources/application-dev.yml"))))
            env.getPropertySources().addLast(source);
        return env;
    }
    static String url(StandardEnvironment env,String db) {
        String original=env.getRequiredProperty("spring.datasource.url");
        return original.substring(0,original.indexOf('/',"jdbc:mysql://".length())+1)+db
                +"?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&connectTimeout=5000&socketTimeout=10000";
    }
    static DriverManagerDataSource source(StandardEnvironment env,String db) {
        return new DriverManagerDataSource(url(env,db),env.getRequiredProperty("spring.datasource.username"),env.getRequiredProperty("spring.datasource.password"));
    }
    static synchronized void write(String file,Object value) throws Exception {
        Files.writeString(output.resolve(file),JSON.writeValueAsString(value)+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
    }
    @Test void realVideoThenKilledWorker() throws Exception {
        var env=credentials();
        database="videoai_live_it_"+UUID.randomUUID().toString().replace("-","");
        output=ROOT.resolve("logs").resolve(database);Files.createDirectories(output);
        Files.writeString(ROOT.resolve("logs/database-live-latest.txt"),output.toString());
        var admin=new JdbcTemplate(source(env,""));
        admin.execute("CREATE DATABASE `"+database+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        var ds=source(env,database);var jdbc=new JdbcTemplate(ds);
        String schema=Files.readString(ROOT.resolve("sql/schema.sql")).replaceAll("(?im)^CREATE DATABASE[^;]*;","").replaceAll("(?im)^USE [^;]*;","");
        new ResourceDatabasePopulator(new ByteArrayResource(schema.getBytes(StandardCharsets.UTF_8))).execute(ds);
        // 复制只读知识数据，使真实检索可用；分析与上传数据完全隔离。
        String original=env.getRequiredProperty("spring.datasource.url");
        String oldDb=original.substring(original.indexOf('/',"jdbc:mysql://".length())+1).split("\\?")[0];
        if(!oldDb.matches("[A-Za-z0-9_]+"))throw new IllegalStateException("源库名不安全");
        for(String table:List.of("knowledge_base","knowledge_card","knowledge_chunk")) {
            var columns=jdbc.queryForList("SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? ORDER BY ORDINAL_POSITION",String.class,database,table);
            var existing=jdbc.queryForList("SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME=?",String.class,oldDb,table);
            String names=String.join(",",columns.stream().filter(existing::contains).map(c->"`"+c+"`").toList());
            jdbc.execute("INSERT INTO `"+table+"` ("+names+") SELECT "+names+" FROM `"+oldDb+"`.`"+table+"`");
        }
        write("run.jsonl",Map.of("database",database,"video","D:/data/video/3.MP4","startedAt",Instant.now().toString()));
        try(var app=new SpringApplicationBuilder(VideoWorkerApplication.class,AuditConfig.class).web(WebApplicationType.NONE)
                .run("--spring.profiles.active=dev","--spring.datasource.url="+url(env,database),
                        "--analysis.media.ffmpeg=D:/software/tools/oopz/ffmpeg.exe",
                        "--analysis.media.ffprobe=D:/software/dev/Trae/Trae CN/resources/app/bin/ffprobe.exe",
                        "--analysis.media.temp-root="+output.resolve("media"),
                        "--spring.main.banner-mode=off","--logging.level.root=WARN")) {
            var effective=app.getEnvironment();
            var models=new LinkedHashMap<String,Object>();
            for(String key:List.of("ai.provider","ai.dashscope.model","analysis.asr.model","analysis.text.model","videoai.rag.embedding.model","videoai.rag.rerank-model"))
                models.put(key,effective.getProperty(key,"default"));
            write("models.jsonl",models);
            String key=app.getBean(StorageService.class).putArtifact(Path.of("D:/data/video/3.MP4"),"video/mp4");
            write("run.jsonl",Map.of("sourceObjectKey",key));
            jdbc.update("INSERT INTO analysis_task(task_id,task_name,upload_id,video_url,user_id,status,attempt_no,prompt) VALUES ('live-video-3','3.MP4真实链路验收','acceptance-upload',?,1,'PENDING',0,?)",key,"请复盘这局 Apex 对局，重点分析交战决策、掩体与技能使用。只根据实际画面判断英雄和地图，无法确认时明确说明。请用中文输出。");
            String last="";long end=System.nanoTime()+TimeUnit.MINUTES.toNanos(40);
            while(System.nanoTime()<end) {
                var row=jdbc.queryForMap("SELECT status,current_step,attempt_no,error_code,started_at,finished_at FROM analysis_task WHERE task_id='live-video-3'");
                String state=row.get("status")+"/"+row.get("current_step");
                if(!state.equals(last)){write("stages.jsonl",Map.of("time",Instant.now().toString(),"state",state));System.out.println("LIVE_STAGE "+state);last=state;}
                if(!Set.of("PENDING","RUNNING").contains(row.get("status")))break;
                Thread.sleep(2000);
            }
            for(String table:List.of("analysis_task","analysis_execution","analysis_asr_part","analysis_text_call","analysis_segment"))
                write(table+".jsonl",jdbc.queryForList("SELECT * FROM "+table+" WHERE task_id='live-video-3'"));
            assertEquals("SUCCEEDED",jdbc.queryForObject("SELECT status FROM analysis_task WHERE task_id='live-video-3'",String.class),"真实视频链路未成功，保留数据排查，不自动重跑付费任务");
            assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM analysis_segment WHERE task_id='live-video-3' AND status='SUCCEEDED'",Integer.class)>0,"必须实际分析至少一个片段");
        } finally {
            write("run.jsonl",Map.of("liveEndedAt",Instant.now().toString()));
        }
        verifyProcessCrash(env,jdbc);
        System.out.println("LIVE_AND_CRASH_ACCEPTANCE_OK output="+output);
    }

    static void verifyProcessCrash(StandardEnvironment env,JdbcTemplate jdbc) throws Exception {
        if(!database.matches("videoai_live_it_[a-f0-9]{32}"))throw new IllegalStateException("仅允许验收库");
        jdbc.update("DELETE FROM analysis_task WHERE task_id='crash-probe'");
        jdbc.update("INSERT INTO analysis_task(task_id,upload_id,video_url,user_id,status,attempt_no) VALUES ('crash-probe','acceptance-upload','no-external-call',1,'PENDING',0)");
        var child=startChild(false);
        try {
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
            while(System.nanoTime()<end && jdbc.queryForObject("SELECT COUNT(*) FROM analysis_task WHERE task_id='crash-probe' AND current_step='CRASH_PROBE'",Integer.class)==0) {
                assertTrue(child.isAlive(),"子进程提前退出");Thread.sleep(100);
            }
            assertEquals("CRASH_PROBE",jdbc.queryForObject("SELECT current_step FROM analysis_task WHERE task_id='crash-probe'",String.class));
            var before=jdbc.queryForMap("SELECT owner_token,lease_until FROM analysis_task WHERE task_id='crash-probe'");
            var beforeLease=jdbc.queryForObject("SELECT lease_until FROM analysis_task WHERE task_id='crash-probe'",java.time.LocalDateTime.class);
            // 等待实际续租，随后强杀真实 JVM，不使用 shutdown hook 或直接改过期时间。
            Thread.sleep(22000);
            var renewed=jdbc.queryForMap("SELECT owner_token,lease_until FROM analysis_task WHERE task_id='crash-probe'");
            assertTrue(jdbc.queryForObject("SELECT lease_until FROM analysis_task WHERE task_id='crash-probe'",java.time.LocalDateTime.class).isAfter(beforeLease));
            child.destroyForcibly();assertTrue(child.waitFor(10,TimeUnit.SECONDS));
            write("crash.jsonl",Map.of("event","FORCE_KILLED","time",Instant.now().toString(),"pid",child.pid()));
            var repo=new TaskDispatchRepository(source(env,database),new DataSourceTransactionManager(source(env,database)),90);
            var recovery=startChild(true);
            try {
                write("crash.jsonl",Map.of("event","RESTARTED_NEW_JVM","pid",recovery.pid(),"time",Instant.now().toString()));
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(110);
                while(System.nanoTime()<deadline && "RUNNING".equals(jdbc.queryForObject("SELECT status FROM analysis_task WHERE task_id='crash-probe'",String.class))) {
                    assertTrue(recovery.isAlive(),"恢复进程提前退出");Thread.sleep(1000);
                }
                assertEquals("EXECUTION_INTERRUPTED",jdbc.queryForObject("SELECT error_code FROM analysis_task WHERE task_id='crash-probe'",String.class));
                assertEquals("CRASH_PROBE",jdbc.queryForObject("SELECT current_step FROM analysis_task WHERE task_id='crash-probe'",String.class),"过期任务不能自动重放");
                assertFalse(repo.finish(new TaskDispatchRepository.Lease("crash-probe",0,(String)before.get("owner_token")),"SUCCEEDED",null));
                write("crash.jsonl",Map.of("event","EXPIRED_FAILED_WITHOUT_REPLAY","time",Instant.now().toString()));
                assertEquals(1,jdbc.update("UPDATE analysis_task SET status='PENDING',attempt_no=attempt_no+1,owner_token=NULL,lease_until=NULL,error_code=NULL,finished_at=NULL,started_at=NULL WHERE task_id='crash-probe' AND status='FAILED'"));
                write("crash.jsonl",Map.of("event","MANUAL_RETRY_REQUESTED","time",Instant.now().toString()));
                long finish=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
                while(System.nanoTime()<finish && !"SUCCEEDED".equals(jdbc.queryForObject("SELECT status FROM analysis_task WHERE task_id='crash-probe'",String.class))) {
                    assertTrue(recovery.isAlive(),"恢复进程提前退出");Thread.sleep(300);
                }
                assertEquals("SUCCEEDED",jdbc.queryForObject("SELECT status FROM analysis_task WHERE task_id='crash-probe'",String.class));
                assertEquals("RECOVERY_EXECUTED",jdbc.queryForObject("SELECT current_step FROM analysis_task WHERE task_id='crash-probe'",String.class));
                assertEquals(1,jdbc.queryForObject("SELECT attempt_no FROM analysis_task WHERE task_id='crash-probe'",Integer.class));
            } finally {if(recovery.isAlive()){recovery.destroyForcibly();assertTrue(recovery.waitFor(10,TimeUnit.SECONDS));}}
            write("crash.jsonl",Map.of("event","PASSED","renewed",true,"automaticReplay",false,"oldOwnerRejected",true,"manualRetryCompleted",true,"time",Instant.now().toString()));
        } finally {if(child.isAlive()){child.destroyForcibly();child.waitFor(10,TimeUnit.SECONDS);}}
    }
    static Process startChild(boolean recover) throws Exception {
        String cp=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")).replace('\\','/');
        Path argsFile=output.resolve(recover?"recovery.args":"crash.args");
        Files.writeString(argsFile,"-cp\n\""+cp+"\"\n"+DatabaseLiveVideoAcceptanceTest.class.getName()+"\n"+database+(recover?"\nrecover":"")+"\n");
        return new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java.exe").toString(),"@"+argsFile)
                .directory(ROOT.resolve("video-worker").toFile()).redirectErrorStream(true)
                .redirectOutput(output.resolve(recover?"crash-recovery.log":"crash-child.log").toFile()).start();
    }
    public static void main(String[] args) throws Exception {
        if(args.length<1 || args.length>2 || !args[0].matches("videoai_live_it_[a-f0-9]{32}"))throw new IllegalArgumentException("仅允许验收库");
        var ds=source(credentials(),args[0]);var jdbc=new JdbcTemplate(ds);
        var repo=new TaskDispatchRepository(ds,new DataSourceTransactionManager(ds),90);
        try(var scheduler=new DatabaseTaskScheduler(repo,context->{
            if(args.length==2 && "recover".equals(args[1])) {
                System.out.println("RECOVERY_WORK "+context.lease().taskId()+" attempt="+context.lease().attemptNo());
                jdbc.update("UPDATE analysis_task SET current_step='RECOVERY_EXECUTED' WHERE task_id=?",context.lease().taskId());
                return DatabaseTaskScheduler.Outcome.succeeded();
            }
            jdbc.update("UPDATE analysis_task SET current_step='CRASH_PROBE' WHERE task_id=?",context.lease().taskId());
            while(true){context.check();Thread.sleep(1000);}
        },3)) {scheduler.start();Thread.sleep(TimeUnit.MINUTES.toMillis(10));}
    }

    @TestConfiguration
    static class AuditConfig { @Bean UsageAspect usageAspect(){return new UsageAspect();} }
    @Aspect
    static class UsageAspect {
        @org.springframework.beans.factory.annotation.Autowired org.springframework.core.env.Environment env;
        @Around("execution(* com.videoai.worker.asr.CloudAsrClient.*(..)) || execution(* com.videoai.worker.screening.AiTextClient.complete(..)) || execution(* com.videoai.worker.service.provider.AiVideoProvider.callDetailed(..)) || execution(* com.videoai.infra.rag.vector.EmbeddingProvider.embedQuery(..)) || execution(* com.videoai.rag.service.RerankService.rerank(..))")
        Object call(ProceedingJoinPoint point) throws Throwable {
            String method=point.getSignature().getName();String type=point.getTarget().getClass().getSimpleName();
            var event=new LinkedHashMap<String,Object>();event.put("callId",UUID.randomUUID().toString());event.put("service",type);event.put("operation",method);
            event.put("startedAt",Instant.now().toString());var owner=ExecutionOwnership.current();
            if(owner!=null){event.put("taskId",owner.taskId);event.put("attemptNo",owner.executionNo);}
            String modelKey=type.contains("Asr")?"analysis.asr.model":type.contains("Text")?"analysis.text.model":type.contains("Embedding")?"videoai.rag.embedding.model":type.contains("Rerank")?"videoai.rag.rerank-model":"ai.dashscope.model";
            event.put("model",env.getProperty(modelKey,"unknown"));event.put("status","STARTED");write("external-calls.jsonl",event);
            long start=System.nanoTime();
            try(var receipt=ExternalUsageReceipt.listen((usage,id)->{try{event.put("usage",JSON.readTree(usage));event.put("requestId",id);}catch(Exception e){event.put("usageParseError",true);}})) {
                Object result=point.proceed();
                if(result instanceof AiVideoProvider.DetailedResult video){event.put("usage",video.usageJson()==null?null:JSON.readTree(video.usageJson()));event.put("requestId",video.requestId());}
                else if(result instanceof CloudAsrClient.Query query){event.put("usage",query.usage());event.put("remoteStatus",query.status());event.put("remoteTaskId",point.getArgs()[0]);}
                else if(method.equals("complete") && result instanceof String body){var response=JSON.readTree(body);event.put("usage",response.get("usage"));event.put("requestId",response.path("id").asText());}
                else if(method.equals("submit")){event.put("remoteTaskId",result);}
                event.put("status","RETURNED");return result;
            } catch(Throwable e){event.put("status","FAILED_USAGE_UNKNOWN");event.put("errorType",e.getClass().getSimpleName());throw e;}
            finally {event.put("elapsedMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));write("external-calls.jsonl",event);}
        }
    }
}
