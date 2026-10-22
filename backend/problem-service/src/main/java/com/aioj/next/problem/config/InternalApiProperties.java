package com.aioj.next.problem.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aioj.internal")
public class InternalApiProperties {
    private String apiToken = "";
    private String judgeWorkerBaseUrl = "http://127.0.0.1:8203";

    public String getApiToken() {
        return apiToken;
    }

    public void setApiToken(String apiToken) {
        this.apiToken = apiToken;
    }

    public String getJudgeWorkerBaseUrl() {
        return judgeWorkerBaseUrl;
    }

    public void setJudgeWorkerBaseUrl(String judgeWorkerBaseUrl) {
        this.judgeWorkerBaseUrl = judgeWorkerBaseUrl;
    }
}
