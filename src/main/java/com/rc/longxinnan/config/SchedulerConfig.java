package com.rc.longxinnan.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Clock;

/**
 * 开启定时任务，并暴露一个可配置线程数的 TaskScheduler。
 *
 * <p>默认 poolSize=1：单线程轮询在单实例内不会与自身并发抢单
 * （配合 SKIP LOCKED 认领，避免同 JVM 内出现认领竞态）。
 */
@Configuration
@EnableScheduling
public class SchedulerConfig {

    /** 系统时钟 bean：供失败计数/冷却期等使用，测试可替换。 */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public TaskScheduler taskScheduler(PollerProperties pollerProperties) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(pollerProperties.poolSize());
        scheduler.setThreadNamePrefix("poller-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        return scheduler;
    }
}
