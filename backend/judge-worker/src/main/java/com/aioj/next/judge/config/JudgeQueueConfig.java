package com.aioj.next.judge.config;

import com.aioj.next.contract.judge.JudgeTaskMessage;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration
public class JudgeQueueConfig {
    public static final String JUDGE_EXCHANGE = "aioj.judge.exchange";
    public static final String JUDGE_QUEUE = "aioj.judge.queue";
    public static final String JUDGE_DEAD_LETTER_QUEUE = "aioj.judge.dlq";
    public static final String JUDGE_ROUTING_KEY = "judge.submit";

    @Bean
    DirectExchange judgeExchange() {
        return new DirectExchange(JUDGE_EXCHANGE, true, false);
    }

    @Bean
    Queue judgeQueue() {
        return new Queue(JUDGE_QUEUE, true, false, false, Map.of(
                "x-dead-letter-exchange", JUDGE_EXCHANGE,
                "x-dead-letter-routing-key", "judge.dead"
        ));
    }

    @Bean
    Queue judgeDeadLetterQueue() {
        return new Queue(JUDGE_DEAD_LETTER_QUEUE, true);
    }

    @Bean
    Binding judgeBinding(Queue judgeQueue, DirectExchange judgeExchange) {
        return BindingBuilder.bind(judgeQueue).to(judgeExchange).with(JUDGE_ROUTING_KEY);
    }

    @Bean
    Binding judgeDeadLetterBinding(Queue judgeDeadLetterQueue, DirectExchange judgeExchange) {
        return BindingBuilder.bind(judgeDeadLetterQueue).to(judgeExchange).with("judge.dead");
    }

    @Bean
    JacksonJsonMessageConverter jacksonJsonMessageConverter(JsonMapper jsonMapper) {
        // Spring AMQP 4 trusts no type-id packages by default; allow only the judge message contract.
        return new JacksonJsonMessageConverter(jsonMapper, JudgeTaskMessage.class.getPackageName());
    }
}
