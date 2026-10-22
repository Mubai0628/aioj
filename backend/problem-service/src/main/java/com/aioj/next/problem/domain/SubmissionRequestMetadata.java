package com.aioj.next.problem.domain;

public record SubmissionRequestMetadata(
        String remoteAddress,
        String forwardedFor,
        String userAgent,
        String idempotencyKey
) {
    public SubmissionRequestMetadata(String remoteAddress, String forwardedFor, String userAgent) {
        this(remoteAddress, forwardedFor, userAgent, null);
    }
}
