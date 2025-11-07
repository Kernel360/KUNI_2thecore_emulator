package com.example.emulator.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Configuration
public class SchedulerConfig {

    @Bean(name = "gpxSchedulerPool")
    public ScheduledExecutorService gpxSchedulerPool(){
        return Executors.newScheduledThreadPool(50);
    }

}
