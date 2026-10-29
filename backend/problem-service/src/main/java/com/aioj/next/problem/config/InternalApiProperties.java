package com.aioj.next.problem.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aioj.internal")
public class InternalApiProperties extends com.aioj.next.common.security.InternalServiceProperties {
    private String judgeWorkerBaseUrl = "http://127.0.0.1:8203";


    public String getJudgeWorkerBaseUrl() {
        return judgeWorkerBaseUrl;
    }

    public void setJudgeWorkerBaseUrl(String judgeWorkerBaseUrl) {
        this.judgeWorkerBaseUrl = judgeWorkerBaseUrl;
    }
}
