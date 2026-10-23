package com.aioj.next.sandbox.client;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One go-judge command, serialized as the {@code cmd} element of a
 * {@code POST /run}. Limits are in go-judge units: nanoseconds and bytes.
 * Absent optional parts are left out of the JSON.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Command(
        List<String> args,
        List<String> env,
        List<CommandFile> files,
        Long cpuLimit,
        Long clockLimit,
        Long memoryLimit,
        Integer procLimit,
        Map<String, CopyInFile> copyIn,
        List<String> copyOut,
        List<String> copyOutCached,
        Long copyOutMax
) {
    /** One entry of {@code files}: stdin content, or a named collector with its byte cap. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CommandFile(String content, String name, Integer max) {
        public static CommandFile stdin(String content) {
            return new CommandFile(content == null ? "" : content, null, null);
        }

        public static CommandFile collector(String name, int max) {
            return new CommandFile(null, name, max);
        }
    }

    /** A file placed in the sandbox before the run: inline content, or a cached file id. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CopyInFile(String content, String fileId) {
        public static CopyInFile content(String content) {
            return new CopyInFile(content == null ? "" : content, null);
        }

        public static CopyInFile cached(String fileId) {
            return new CopyInFile(null, fileId);
        }
    }

    public static Builder builder(List<String> args) {
        return new Builder(args);
    }

    public static final class Builder {
        private final List<String> args;
        private List<String> env;
        private final List<CommandFile> files = new ArrayList<>();
        private Long cpuLimit;
        private Long clockLimit;
        private Long memoryLimit;
        private Integer procLimit;
        private Map<String, CopyInFile> copyIn;
        private List<String> copyOut;
        private List<String> copyOutCached;
        private Long copyOutMax;

        private Builder(List<String> args) {
            this.args = List.copyOf(Objects.requireNonNull(args, "args"));
        }

        public Builder env(List<String> variables) {
            this.env = List.copyOf(variables);
            return this;
        }

        /** stdin, then the stdout and stderr collectors: go-judge's three standard files. */
        public Builder standardFiles(String stdin, int stdoutMax, int stderrMax) {
            files.clear();
            files.add(CommandFile.stdin(stdin));
            files.add(CommandFile.collector("stdout", stdoutMax));
            files.add(CommandFile.collector("stderr", stderrMax));
            return this;
        }

        public Builder limits(long cpuLimitNs, long clockLimitNs, long memoryLimitBytes, int processLimit) {
            this.cpuLimit = cpuLimitNs;
            this.clockLimit = clockLimitNs;
            this.memoryLimit = memoryLimitBytes;
            this.procLimit = processLimit;
            return this;
        }

        public Builder copyInContent(String name, String content) {
            copyIn().put(name, CopyInFile.content(content));
            return this;
        }

        public Builder copyInCached(String name, String fileId) {
            copyIn().put(name, CopyInFile.cached(fileId));
            return this;
        }

        public Builder copyOut(List<String> names) {
            this.copyOut = List.copyOf(names);
            return this;
        }

        public Builder copyOutCached(List<String> names) {
            this.copyOutCached = List.copyOf(names);
            return this;
        }

        public Builder copyOutMax(long bytes) {
            this.copyOutMax = bytes;
            return this;
        }

        public Command build() {
            return new Command(args, env, files.isEmpty() ? null : List.copyOf(files), cpuLimit, clockLimit,
                    memoryLimit, procLimit, copyIn == null ? null : Map.copyOf(copyIn), copyOut, copyOutCached,
                    copyOutMax);
        }

        private Map<String, CopyInFile> copyIn() {
            if (copyIn == null) {
                copyIn = new LinkedHashMap<>();
            }
            return copyIn;
        }
    }
}
