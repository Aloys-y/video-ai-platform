package com.videoai.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * API服务启动类
 *
 * 面试重点：
 * 1. @SpringBootApplication包含什么？
 *    - @Configuration: 配置类
 *    - @EnableAutoConfiguration: 自动配置
 *    - @ComponentScan: 组件扫描
 *
 * 2. 为什么要指定scanBasePackages？
 *    - 默认只扫描当前包及子包
 *    - 需要扫描infra模块的Mapper和Config
 */
@SpringBootApplication
@ComponentScan(basePackages = "com.videoai")
public class VideoApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(VideoApiApplication.class, args);
    }
}
