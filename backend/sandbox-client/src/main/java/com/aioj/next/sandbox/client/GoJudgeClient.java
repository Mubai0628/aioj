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

    public GoJudgeClient(SandboxConfig config) {
        this.config = config;
        this.baseUrl = config.baseUrl();
        this.http = HttpClient.newBuilder().connectTimeout(config.connectTimeout()).build();
    }

    /** Runs one command and returns its result. */
    public RunResult run(Command command) {
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
        return results.get(0);
    }

    public void deleteFile(String fileId) {
        send(request(filePath(fileId)).DELETE(), "DELETE /file/{id}", HttpResponse.BodyHandlers.discarding());
    }

    /** Streams a cached file into {@code output}. */
    public void downloadFile(String fileId, OutputStream output) {
        HttpResponse<InputStream> response = send(request(filePath(fileId)).GET(), "GET /file/{id}",
                HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream input = response.body()) {
            input.transferTo(output);
        } catch (IOException ex) {
            throw new SandboxException("Failed to stream sandbox cached file", ex);
        }
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
            throw new SandboxException("Sandbox HTTP " + status + " on " + operation);
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
