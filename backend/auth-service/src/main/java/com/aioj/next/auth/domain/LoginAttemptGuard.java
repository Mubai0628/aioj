package com.aioj.next.auth.domain;

/**
 * Throttles repeated login failures per account to blunt online brute-force attempts.
 * Implementations must be safe for concurrent use.
 */
public interface LoginAttemptGuard {
    /**
     * Throws {@link com.aioj.next.common.error.RateLimitedException} (429 with Retry-After) when
     * the account is currently locked for this client address, or for every address.
     *
     * @param clientIp the address the gateway resolved ({@code X-Aioj-Client-Ip}); null when unknown
     */
    void ensureNotLocked(String account, String clientIp);
    void recordFailure(String account, String clientIp);
    void recordSuccess(String account, String clientIp);
}
