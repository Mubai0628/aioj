package com.aioj.next.sandbox.client;

/**
 * A go-judge request that did not produce a usable answer. Messages name the
 * method, the path and the status only: never the sandbox host, the response
 * body or the token, because callers put them into judge results.
 */
public class SandboxException extends RuntimeException {
    private final Integer httpStatus;

    public SandboxException(String message) {
        this(message, null, null);
    }

    public SandboxException(String message, Throwable cause) {
        this(message, cause, null);
    }

    SandboxException(String message, Throwable cause, Integer httpStatus) {
        super(message, cause);
        this.httpStatus = httpStatus;
    }

    /**
     * The status when the sandbox answered with a non-2xx one, null for a timeout or an I/O failure
     * (T2.5): logs that leave out messages can still say what went wrong.
     */
    public Integer httpStatus() {
        return httpStatus;
    }
}
