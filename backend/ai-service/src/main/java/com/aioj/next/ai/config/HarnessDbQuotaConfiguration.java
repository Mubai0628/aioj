package com.aioj.next.ai.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.time.Duration;

/**
 * Puts {@link HarnessDbQuotaDataSource} in front of the application DataSource (plan T1.6). The
 * quota is the Hikari pool size minus one; the wait is
 * {@code aioj.ai.agent-core.context.db-quota-wait-ms} (default 2000).
 */
@Configuration(proxyBeanMethods = false)
public class HarnessDbQuotaConfiguration {
    private static final Logger log = LoggerFactory.getLogger(HarnessDbQuotaConfiguration.class);

    @Bean
    static BeanPostProcessor harnessDbQuotaDataSourcePostProcessor(Environment environment) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (!(bean instanceof DataSource dataSource) || !"dataSource".equals(beanName)
                        || bean instanceof HarnessDbQuotaDataSource) {
                    return bean;
                }
                int poolSize = environment.getProperty("spring.datasource.hikari.maximum-pool-size", Integer.class, 10);
                long waitMs = environment.getProperty("aioj.ai.agent-core.context.db-quota-wait-ms", Long.class, 2_000L);
                HarnessDbQuota quota = new HarnessDbQuota(HarnessDbQuota.permitsFor(poolSize), Duration.ofMillis(waitMs));
                log.info("Harness database quota: {} of {} pool connections, wait {} ms", quota.capacity(), poolSize, waitMs);
                return new HarnessDbQuotaDataSource(dataSource, quota);
            }
        };
    }
}
