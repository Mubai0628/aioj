package com.aioj.next.problem.domain;

/** @param clientIp the client address the gateway resolved ({@code X-Aioj-Client-Ip}), null without the gateway */
public record SubmissionRequestMetadata(
        String remoteAddress,
        String clientIp,
        String userAgent,
        String idempotencyKey
) {
    public SubmissionRequestMetadata(String remoteAddress, String clientIp, String userAgent) {
        this(remoteAddress, clientIp, userAgent, null);
    }
}
