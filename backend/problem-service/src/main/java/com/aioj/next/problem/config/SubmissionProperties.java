package com.aioj.next.problem.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aioj.submission")
public class SubmissionProperties {
    /** New submissions a user may create per minute; 0 disables the limit. */
    private int rateLimitPerMinute = 30;

    public int getRateLimitPerMinute() {
        return rateLimitPerMinute;
    }

    public void setRateLimitPerMinute(int rateLimitPerMinute) {
        this.rateLimitPerMinute = rateLimitPerMinute;
    }
}
