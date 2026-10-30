package com.aioj.next.common.error;

/**
 * A limit turned the request away (T2.2): HTTP 429 with {@code Retry-After}, the seconds also in
 * {@code errorParams.retryAfterSeconds}, and a count on {@code aioj.ratelimit.rejected} by limiter
 * and dimension.
 */
public class RateLimitedException extends DomainException {
    private final String limiter;
    private final String dimension;
    private final long retryAfterSeconds;

    /**
     * @param limiter   which limit, for the meter: {@code login}, {@code submission}, {@code ai.turns}...
     * @param dimension what it counts by: {@code user}, {@code account}, {@code account_ip}, {@code site}
     * @param message   one of the messages {@link UserErrorFeedback} maps to an error key
     */
    public RateLimitedException(String limiter, String dimension, long retryAfterSeconds, String message) {
        super(ErrorCode.TOO_MANY_REQUESTS, message);
        this.limiter = limiter;
        this.dimension = dimension;
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public String limiter() {
        return limiter;
    }

    public String dimension() {
        return dimension;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
