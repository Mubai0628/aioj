package com.aioj.next.ai.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Plan 15.4: the scheduled tasks share a pool of 4 threads from application.yml, not one thread. */
class SchedulingPoolTest {

    @Test
    void applicationYmlGivesTheScheduledTasksFourThreads() throws IOException {
        runner().run(context -> assertThat(poolSize(context.getBean(ThreadPoolTaskScheduler.class))).isEqualTo(4));
    }

    @Test
    void theEnvironmentCanChangeThePoolSize() throws IOException {
        runner().withPropertyValues("AI_SCHEDULING_POOL_SIZE=6")
                .run(context -> assertThat(poolSize(context.getBean(ThreadPoolTaskScheduler.class))).isEqualTo(6));
    }

    private static ApplicationContextRunner runner() throws IOException {
        List<PropertySource<?>> yaml = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));
        return new ApplicationContextRunner()
                .withInitializer(context -> yaml.forEach(source ->
                        context.getEnvironment().getPropertySources().addLast(source)))
                .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
                .withUserConfiguration(Scheduling.class);
    }

    private static int poolSize(ThreadPoolTaskScheduler scheduler) {
        return scheduler.getScheduledThreadPoolExecutor().getCorePoolSize();
    }

    @EnableScheduling
    @Configuration(proxyBeanMethods = false)
    static class Scheduling {
    }
}
