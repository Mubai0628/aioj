package com.aioj.next.sandbox.client;

import java.util.List;

/** go-judge statuses as machine-readable codes; a collector over its cap counts as the output limit. */
public enum ResultCode {
    ACCEPTED,
    TIME_LIMIT_EXCEEDED,
    MEMORY_LIMIT_EXCEEDED,
    OUTPUT_LIMIT_EXCEEDED,
    RUNTIME_ERROR,
    /** Never sent by go-judge; callers use it for a failed compile step. */
    COMPILE_ERROR,
    SYSTEM_ERROR;

    public static ResultCode of(RunResult result) {
        return of(result.status(), result.fileError());
    }

    public static ResultCode of(String status, List<RunResult.FileError> fileErrors) {
        if (fileErrors != null && fileErrors.stream().anyMatch(error -> "CollectSizeExceeded".equals(error.type()))) {
            return OUTPUT_LIMIT_EXCEEDED;
        }
        if (status == null) {
            return SYSTEM_ERROR;
        }
        return switch (status) {
            case "Accepted" -> ACCEPTED;
            case "Time Limit Exceeded" -> TIME_LIMIT_EXCEEDED;
            case "Memory Limit Exceeded" -> MEMORY_LIMIT_EXCEEDED;
            case "Output Limit Exceeded" -> OUTPUT_LIMIT_EXCEEDED;
            case "Nonzero Exit Status", "Non Zero Exit Status", "Signalled", "Dangerous Syscall" -> RUNTIME_ERROR;
            case "Compile Error" -> COMPILE_ERROR;
            default -> SYSTEM_ERROR;
        };
    }
}
