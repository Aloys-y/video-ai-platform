package com.videoai.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Worker服务启动类
 *
 * 面试重点：
 * 1. API服务和Worker服务为什么分开？
 *    - 职责不同：API面向用户，Worker面向任务
 *    - 扩展不同：API水平扩展，Worker按队列深度扩展
 *    - 资源不同：API用普通机器，Worker可能需要GPU
 *
 * 2. @EnableAsync的作用？
 *    - 开启异步处理
 *    - 视频抽帧、AI调用可以异步并行
 */
@SpringBootApplication
@ComponentScan(basePackages = "com.videoai")
@EnableAsync
public class VideoWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(VideoWorkerApplication.class, args);
    }
}
