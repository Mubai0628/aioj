package com.aioj.next.sandbox.client;

/**
 * A go-judge request that did not produce a usable answer. Messages name the
 * method, the path and the status only: never the sandbox host, the response
 * body or the token, because callers put them into judge results.
 */
public class SandboxException extends RuntimeException {
    public SandboxException(String message) {
        super(message);
    }

    public SandboxException(String message, Throwable cause) {
        super(message, cause);
    }
}
