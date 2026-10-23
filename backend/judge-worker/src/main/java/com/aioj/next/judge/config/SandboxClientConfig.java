package com.aioj.next.judge.config;

import com.aioj.next.sandbox.client.DefaultOutputComparator;
import com.aioj.next.sandbox.client.OutputComparator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** sandbox-client has no Spring; its comparator becomes a bean here. */
@Configuration
public class SandboxClientConfig {
    @Bean
    OutputComparator outputComparator() {
        return new DefaultOutputComparator();
    }
}
