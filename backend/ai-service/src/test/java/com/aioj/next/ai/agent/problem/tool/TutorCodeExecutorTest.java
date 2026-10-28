package com.aioj.next.ai.agent.problem.tool;

import com.aioj.next.agent.harness.sandbox.api.ExecutionFeedback;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TutorCodeExecutorTest {

    @Test
    void compileSendsTheCacheScopeAndReadsTheHit() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://executor");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://executor/api/v1/internal/sandbox/compile"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("\"cacheScope\":\"user:7\"")))
                .andRespond(withSuccess("""
                        {"code":0,"message":"ok","data":{"status":"COMPILE_ERROR","message":"failed","fileId":null,
                        "stderr":"main.cpp:1:5: error: expected ';'","timeMillis":30,"memoryKb":4096,"exitStatus":1,
                        "runTimeMillis":31,"procPeak":2,"cacheHit":false}}
                        """, MediaType.APPLICATION_JSON));

        TutorCodeExecutor.Compiled compiled = new TutorCodeExecutor(builder.build()).compile("cpp", "int main(", "user:7");

        assertThat(compiled.feedback().status()).isEqualTo(ExecutionFeedback.Status.COMPILE_ERROR);
        assertThat(compiled.feedback().verdict()).isEqualTo(ExecutionFeedback.Verdict.CE);
        assertThat(compiled.feedback().diagnostics()).singleElement().satisfies(d -> assertThat(d.line()).isEqualTo(1));
        assertThat(compiled.feedback().processPeak()).isEqualTo(2);
        assertThat(compiled.cacheHit()).isFalse();
        server.verify();
    }

    @Test
    void aBatchMapsEachRunAndASandboxFailureIsASystemError() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://executor");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://executor/api/v1/internal/sandbox/run-batch"))
                .andExpect(content().string(containsString("\"timeLimitMillis\":5000")))
                .andRespond(withSuccess("""
                        {"code":0,"message":"ok","data":{"results":[
                        {"status":"ACCEPTED","stdout":"3\\n","stderr":"","timeMillis":2,"memoryKb":1024,"procPeak":1},
                        {"status":"RUNTIME_ERROR","stdout":"","stderr":"Segmentation fault","timeMillis":3,"memoryKb":1024},
                        {"status":"SYSTEM_ERROR","message":"sandbox broke"}]}}
                        """, MediaType.APPLICATION_JSON));

        TutorCodeExecutor.Batch batch = new TutorCodeExecutor(builder.build())
                .run("cpp", "int main(){}", List.of("1 2", "x", "y"), 60_000, null, "user:7");

        assertThat(batch.failure()).isNull();
        assertThat(batch.runs()).extracting(ExecutionFeedback::verdict)
                .containsExactly(null, ExecutionFeedback.Verdict.RE, ExecutionFeedback.Verdict.SE);
        assertThat(batch.runs().get(1).stderrSummary()).isEqualTo("Segmentation fault");
        server.verify();
    }

    @Test
    void aRefusalIsRejectedAndAnUnreachableExecutorUnavailable() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://executor");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://executor/api/v1/internal/sandbox/run-batch")).andRespond(withBadRequest());

        TutorCodeExecutor.Batch refused = new TutorCodeExecutor(builder.build())
                .run("cpp", "int main(){}", List.of("1"), null, null, "user:7");
        assertThat(refused.failure().status()).isEqualTo(ExecutionFeedback.Status.REJECTED);

        TutorCodeExecutor.Batch down = new TutorCodeExecutor(RestClient.builder().baseUrl("http://127.0.0.1:9").build())
                .run("cpp", "int main(){}", List.of("1"), null, null, "user:7");
        assertThat(down.failure().status()).isEqualTo(ExecutionFeedback.Status.UNAVAILABLE);
    }
}
