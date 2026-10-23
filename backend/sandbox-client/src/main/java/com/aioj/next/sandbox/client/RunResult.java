package com.aioj.next.sandbox.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * One element of go-judge's {@code /run} answer. Times are nanoseconds and
 * memory is bytes, as go-judge reports them; {@link Limits} converts.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RunResult(
        String status,
        Integer exitStatus,
        Long time,
        Long memory,
        Long runTime,
        Integer procPeak,
        Map<String, String> files,
        String error,
        List<FileError> fileError,
        Map<String, String> fileIds
) {
    public static final String ACCEPTED = "Accepted";

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FileError(String name, String type, String message) {
    }

    public boolean accepted() {
        return ACCEPTED.equals(status);
    }

    /** A collected file's content, or null. */
    public String file(String name) {
        return files == null ? null : files.get(name);
    }

    /** The cache id of a {@code copyOutCached} file, or null. */
    public String cachedFileId(String name) {
        return fileIds == null ? null : fileIds.get(name);
    }

    /** Whether a collector hit its byte cap, which go-judge reports as a file error. */
    public boolean collectSizeExceeded() {
        return fileError != null && fileError.stream().anyMatch(error -> "CollectSizeExceeded".equals(error.type()));
    }

    /** The status to report: a collector over its cap is the output limit it stands for. */
    public String reportedStatus() {
        return collectSizeExceeded() ? "Output Limit Exceeded" : status;
    }
}
