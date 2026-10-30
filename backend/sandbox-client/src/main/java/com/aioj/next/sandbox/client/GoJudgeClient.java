package com.aioj.next.sandbox.client;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * go-judge over the JDK HTTP client: {@code POST /run}, and
 * {@code GET}/{@code DELETE /file/{id}} for cached files. The connect timeout
 * applies to new connections and the read timeout to each whole request.
 * Every failure is a {@link SandboxException} whose message names only the
 * method, path and status.
 */
public final class GoJudgeClient {
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<RunResult>> RUN_RESULTS = new TypeReference<>() {
    };

    private final SandboxConfig config;
    private final String baseUrl;
    private final HttpClient http;
    private final RequestListener listener;

    public GoJudgeClient(SandboxConfig config) {
        this(config, RequestListener.NONE);
    }

    /** @param listener told about every request once it ends; the services turn it into meters (T2.5) */
    public GoJudgeClient(SandboxConfig config, RequestListener listener) {
        this.config = config;
        this.baseUrl = config.baseUrl();
        this.http = HttpClient.newBuilder().connectTimeout(config.connectTimeout()).build();
        this.listener = listener == null ? RequestListener.NONE : listener;
    }

    /** Runs one command and returns its result. */
    public RunResult run(Command command) {
        long started = System.nanoTime();
        String outcome = RequestListener.FAILED;
        try {
            byte[] body = JSON.writeValueAsBytes(Map.of("cmd", List.of(command)));
            HttpRequest.Builder request = request("/run")
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            HttpResponse<byte[]> response = send(request, "POST /run", HttpResponse.BodyHandlers.ofByteArray());
            List<RunResult> results;
            try {
                results = JSON.readValue(response.body(), RUN_RESULTS);
            } catch (JacksonException ex) {
                throw new SandboxException("Sandbox returned an unreadable /run response", ex);
            }
            if (results == null || results.isEmpty() || results.get(0) == null) {
                throw new SandboxException("Sandbox returned empty /run response");
            }
            outcome = ResultCode.of(results.get(0)).name();
            return results.get(0);
        } catch (SandboxException ex) {
            outcome = outcomeOf(ex);
            throw ex;
        } finally {
            completed("run", outcome, started);
        }
    }

    public void deleteFile(String fileId) {
        long started = System.nanoTime();
        String outcome = RequestListener.FAILED;
        try {
            send(request(filePath(fileId)).DELETE(), "DELETE /file/{id}", HttpResponse.BodyHandlers.discarding());
            outcome = RequestListener.OK;
        } catch (SandboxException ex) {
            outcome = outcomeOf(ex);
            throw ex;
        } finally {
            completed("delete_file", outcome, started);
        }
    }

    /** Streams a cached file into {@code output}. */
    public void downloadFile(String fileId, OutputStream output) {
        long started = System.nanoTime();
        String outcome = RequestListener.FAILED;
        try {
            HttpResponse<InputStream> response = send(request(filePath(fileId)).GET(), "GET /file/{id}",
                    HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = response.body()) {
                input.transferTo(output);
            } catch (IOException ex) {
                throw new SandboxException("Failed to stream sandbox cached file", ex);
            }
            outcome = RequestListener.OK;
        } catch (SandboxException ex) {
            outcome = outcomeOf(ex);
            throw ex;
        } finally {
            completed("download_file", outcome, started);
        }
    }

    /** {@code HTTP_404} and the like when the sandbox answered, {@link RequestListener#FAILED} otherwise. */
    private static String outcomeOf(SandboxException ex) {
        return ex.httpStatus() == null ? RequestListener.FAILED : "HTTP_" + ex.httpStatus();
    }

    /** A listener that throws must never fail the sandbox call it was told about. */
    private void completed(String operation, String outcome, long startedNanos) {
        try {
            listener.completed(operation, outcome, System.nanoTime() - startedNanos);
        } catch (RuntimeException ignored) {
            // Metrics are best effort.
        }
    }

    /** Told about each sandbox request once it ends: what it was, how it ended and how long it took. */
    @FunctionalInterface
    public interface RequestListener {
        String OK = "OK";
        String FAILED = "FAILED";
        RequestListener NONE = (operation, outcome, elapsedNanos) -> { };

        /**
         * @param operation {@code run}, {@code delete_file} or {@code download_file}
         * @param outcome   the {@link ResultCode} name of a run that came back, {@link #OK} for a file
         *                  request that succeeded, {@code HTTP_<status>} when the sandbox refused it,
         *                  {@link #FAILED} when it did not answer
         */
        void completed(String operation, String outcome, long elapsedNanos);
    }

    private HttpRequest.Builder request(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(config.readTimeout());
        if (config.hasToken()) {
            builder.header("Authorization", "Bearer " + config.token());
        }
        return builder;
    }

    private <T> HttpResponse<T> send(HttpRequest.Builder request, String operation, HttpResponse.BodyHandler<T> handler) {
        HttpResponse<T> response;
        try {
            response = http.send(request.build(), handler);
        } catch (HttpTimeoutException ex) {
            throw new SandboxException("Sandbox request timed out on " + operation, ex);
        } catch (IOException ex) {
            throw new SandboxException("Sandbox request failed on " + operation + ": " + ex.getClass().getSimpleName(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new SandboxException("Sandbox request was interrupted on " + operation, ex);
        }
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            if (response.body() instanceof InputStream stream) {
                try {
                    stream.close();
                } catch (IOException ignored) {
                    // The status is what matters; the body is not read.
                }
            }
            throw new SandboxException("Sandbox HTTP " + status + " on " + operation, null, status);
        }
        return response;
    }

    private static String filePath(String fileId) {
        if (fileId == null || fileId.isBlank()) {
            throw new IllegalArgumentException("fileId must not be blank");
        }
        return "/file/" + URLEncoder.encode(fileId, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
