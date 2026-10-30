package com.aioj.next.common.security;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T2.7 (appendix E.6): RabbitMQ sizes its memory watermark from the memory it is told it has. Left
 * alone it read the host's (4.7 GB against a 256/512 MiB container) and would be OOM-killed before
 * flow control began, so each compose file tells it its container limit.
 */
class BrokerMemoryComposeTest {
    private static final Pattern OVERRIDE = Pattern.compile("-rabbit total_memory_available_override_value (\\d+)");

    @Test
    void theBrokerIsToldItsContainerLimit() throws Exception {
        for (String file : new String[]{"compose.yml", "compose.production.yml"}) {
            Map<String, Object> compose = new Yaml().load(Files.readString(Path.of("..", "..", "deploy", file)));
            Map<?, ?> rabbit = (Map<?, ?>) ((Map<?, ?>) compose.get("services")).get("rabbitmq");
            Map<?, ?> environment = (Map<?, ?>) rabbit.get("environment");
            Matcher override = OVERRIDE.matcher(String.valueOf(environment.get("RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS")));
            String limit = String.valueOf(rabbit.get("mem_limit")).toLowerCase();

            assertThat(override.find()).as(file + " passes the override").isTrue();
            assertThat(limit).as(file + " mem_limit").endsWith("m");
            assertThat(Long.parseLong(override.group(1)))
                    .as(file + ": the memory RabbitMQ is told it has is its container limit")
                    .isEqualTo(Long.parseLong(limit.substring(0, limit.length() - 1)) * 1024 * 1024);
        }
    }
}
