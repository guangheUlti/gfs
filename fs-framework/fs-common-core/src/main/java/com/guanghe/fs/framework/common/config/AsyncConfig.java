package com.guanghe.fs.framework.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.VirtualThreadTaskExecutor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler;

import java.util.concurrent.Executor;

/**
 * 异步任务配置
 * 用于文件上传异步处理
 *
 * @Author: guangheUlti
 * @Date: 2025/11/07
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    /**
     * 配置@Async默认使用的虚拟线程池
     */
    @Bean
    public Executor asyncVirtualThreadExecutor() {

        return new VirtualThreadTaskExecutor("async-virtual-");
    }

    /**
     * 配置定时任务虚拟线程
     */
    @Bean
    public TaskScheduler taskScheduler() {
        SimpleAsyncTaskScheduler scheduler = new SimpleAsyncTaskScheduler();
        scheduler.setThreadNamePrefix("scheduler-virtual-");
        scheduler.setVirtualThreads(true);
        return scheduler;
    }

    /**
     * 分片上传虚拟线程池
     */
    @Bean("chunkUploadExecutor")
    public TaskExecutor chunkUploadExecutor() {

        return new VirtualThreadTaskExecutor("chunk-upload-virtual-");
    }

    /**
     * 文件合并虚拟线程池
     */
    @Bean("fileMergeExecutor")
    public TaskExecutor fileMergeExecutor() {

        return new VirtualThreadTaskExecutor("file-merge-virtual-");
    }
}

