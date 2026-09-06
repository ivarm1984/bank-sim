package io.github.ivarm1984.banksim.web;

import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * With {@code @EnableWebSocketMessageBroker} in the context, Spring's STOMP
 * infrastructure registers several of its own {@code Executor} beans
 * (client/broker channel executors, the message broker task scheduler).
 * {@code @Async} then can't pick a default unambiguously, so this names one
 * explicitly - {@code AsyncAnnotationBeanPostProcessor} specifically looks
 * for a bean named {@code taskExecutor} when more than one candidate exists.
 */
@Configuration
public class AsyncConfig {

    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("event-feed-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.initialize();
        return executor;
    }
}
