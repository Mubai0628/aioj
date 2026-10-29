package com.aioj.next.problem.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aioj.plagiarism")
public class PlagiarismProperties {
    private String aiServiceUri = "http://localhost:8204";
    private int maxFragmentExcerptChars = 1200;
    private int executorPoolSize = 2;

    public String getAiServiceUri() {
        return aiServiceUri;
    }

    public void setAiServiceUri(String aiServiceUri) {
        this.aiServiceUri = aiServiceUri;
    }


    public int getMaxFragmentExcerptChars() {
        return maxFragmentExcerptChars;
    }

    public void setMaxFragmentExcerptChars(int maxFragmentExcerptChars) {
        this.maxFragmentExcerptChars = maxFragmentExcerptChars;
    }

    public int getExecutorPoolSize() {
        return executorPoolSize;
    }

    public void setExecutorPoolSize(int executorPoolSize) {
        this.executorPoolSize = executorPoolSize;
    }
}
