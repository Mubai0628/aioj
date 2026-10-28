package com.aioj.next.agent.harness.context;

import com.aioj.next.agent.harness.context.api.AssembledContext;
import com.aioj.next.agent.harness.context.api.ContextAssembler;
import com.aioj.next.agent.harness.context.api.ContextAssemblers;
import com.aioj.next.agent.harness.context.api.ContextContribution;
import com.aioj.next.agent.harness.context.api.ContextContributor;
import com.aioj.next.agent.harness.context.api.ContextPolicyException;
import com.aioj.next.agent.harness.context.api.ContextRequest;
import com.aioj.next.agent.harness.context.api.ContextSection;
import com.aioj.next.agent.harness.context.api.TrustLevel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContextAssemblerTest {
    private static final ContextRequest REQUEST = new ContextRequest("run-1", "chat", "hello",
            Map.of("subject", "u-1"), Duration.ofSeconds(30));
    private static final ThreadLocal<String> CARRIED = new ThreadLocal<>();

    private ContextAssembler assembler = ContextAssemblers.create(2, 4);

    @AfterEach
    void close() {
        assembler.close();
    }

    @Test
    void contributionsComeBackInContributorOrderWithTheirWarnings() {
        AssembledContext context = assembler.assemble(REQUEST, List.of(
                contributor("a", ContextContributor.Kind.POLICY, request -> contribution("a", "first", "a_note")),
                contributor("b", ContextContributor.Kind.OPTIONAL, request -> contribution("b", "second")),
                contributor("c", ContextContributor.Kind.OPTIONAL, request -> null)));

        assertThat(context.sections()).extracting(ContextSection::content).containsExactly("first", "second");
        assertThat(context.contributions()).extracting(ContextContribution::contributor).containsExactly("a", "b", "c");
        assertThat(context.warnings()).containsExactly("a_note");
    }

    @Test
    void aSlowOptionalContributorIsSkippedAndInterruptedWhileTheRestStillRun() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);
        AssembledContext context = assembler.assemble(REQUEST, List.of(
                contributor("slow", ContextContributor.Kind.OPTIONAL, Duration.ofMillis(100), request -> {
                    try {
                        Thread.sleep(10_000);
                    } catch (InterruptedException ex) {
                        interrupted.countDown();
                    }
                    return contribution("slow", "too late");
                }),
                contributor("next", ContextContributor.Kind.OPTIONAL, request -> contribution("next", "still here"))));

        assertThat(context.warnings()).containsExactly("contributor_degraded:slow:timeout");
        assertThat(context.sections()).extracting(ContextSection::content).containsExactly("still here");
        assertThat(interrupted.await(5, TimeUnit.SECONDS)).as("the overrunning contributor is interrupted").isTrue();
    }

    @Test
    void aFailingOptionalContributorCostsOnlyItsOwnSection() {
        AssembledContext context = assembler.assemble(REQUEST, List.of(
                contributor("broken", ContextContributor.Kind.OPTIONAL, request -> {
                    throw new IllegalStateException("store down");
                }),
                contributor("fine", ContextContributor.Kind.OPTIONAL, request -> contribution("fine", "ok"))));

        assertThat(context.warnings()).containsExactly("contributor_degraded:broken:error");
        assertThat(context.sections()).extracting(ContextSection::content).containsExactly("ok");
    }

    @Test
    void aPolicyContributorThatFailsOrOverrunsStopsTheAssembly() {
        AtomicBoolean laterRan = new AtomicBoolean();
        assertThatThrownBy(() -> assembler.assemble(REQUEST, List.of(
                contributor("rules", ContextContributor.Kind.POLICY, request -> {
                    throw new IllegalStateException("rules unavailable");
                }),
                contributor("later", ContextContributor.Kind.OPTIONAL, request -> {
                    laterRan.set(true);
                    return contribution("later", "x");
                }))))
                .isInstanceOfSatisfying(ContextPolicyException.class, ex -> {
                    assertThat(ex.contributor()).isEqualTo("rules");
                    assertThat(ex.reason()).isEqualTo("error");
                    assertThat(ex.getCause()).hasMessage("rules unavailable");
                });
        assertThat(laterRan).isFalse();

        assertThatThrownBy(() -> assembler.assemble(REQUEST, List.of(
                contributor("rules", ContextContributor.Kind.POLICY, Duration.ofMillis(50), request -> {
                    Thread.sleep(5_000);
                    return contribution("rules", "late");
                }))))
                .isInstanceOfSatisfying(ContextPolicyException.class, ex -> assertThat(ex.reason()).isEqualTo("timeout"));
    }

    @Test
    void aFullPoolRejectsInsteadOfQueueingWithoutLimit() throws Exception {
        assembler.close();
        assembler = ContextAssemblers.create(1, 0);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<AssembledContext> occupying = CompletableFuture.supplyAsync(() -> assembler.assemble(REQUEST,
                List.of(contributor("holder", ContextContributor.Kind.OPTIONAL, Duration.ofSeconds(10), request -> {
                    started.countDown();
                    release.await();
                    return contribution("holder", "held");
                }))));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        AssembledContext rejected = assembler.assemble(REQUEST, List.of(
                contributor("extra", ContextContributor.Kind.OPTIONAL, request -> contribution("extra", "x"))));
        assertThat(rejected.warnings()).containsExactly("contributor_degraded:extra:rejected");
        assertThatThrownBy(() -> assembler.assemble(REQUEST, List.of(
                contributor("rules", ContextContributor.Kind.POLICY, request -> contribution("rules", "x")))))
                .isInstanceOfSatisfying(ContextPolicyException.class, ex -> assertThat(ex.reason()).isEqualTo("rejected"));

        release.countDown();
        assertThat(occupying.get(5, TimeUnit.SECONDS).sections()).extracting(ContextSection::content).containsExactly("held");
    }

    @Test
    void eachContributorSeesItsOwnBudgetAndTheDecoratedThread() {
        assembler.close();
        assembler = ContextAssemblers.create(1, 1, task -> () -> {
            CARRIED.set("carried");
            try {
                task.run();
            } finally {
                CARRIED.remove();
            }
        });
        AtomicReference<Duration> seenBudget = new AtomicReference<>();
        AtomicReference<String> seenCarried = new AtomicReference<>();
        assembler.assemble(REQUEST, List.of(contributor("look", ContextContributor.Kind.OPTIONAL, Duration.ofMillis(750),
                request -> {
                    seenBudget.set(request.timeBudget());
                    seenCarried.set(CARRIED.get());
                    return contribution("look", request.attribute("subject", String.class).orElseThrow());
                })));

        assertThat(seenBudget.get()).isEqualTo(Duration.ofMillis(750));
        assertThat(seenCarried.get()).isEqualTo("carried");
    }

    @Test
    void aContributorWithoutAPositiveBudgetCountsAsFailed() {
        AssembledContext context = assembler.assemble(REQUEST, List.of(
                contributor("unbounded", ContextContributor.Kind.OPTIONAL, Duration.ZERO, request -> contribution("unbounded", "x"))));

        assertThat(context.warnings()).containsExactly("contributor_degraded:unbounded:error");
        assertThat(context.sections()).isEmpty();
    }

    @FunctionalInterface
    private interface Body {
        ContextContribution apply(ContextRequest request) throws Exception;
    }

    private static ContextContributor contributor(String name, ContextContributor.Kind kind, Body body) {
        return contributor(name, kind, Duration.ofSeconds(2), body);
    }

    private static ContextContributor contributor(String name, ContextContributor.Kind kind, Duration budget, Body body) {
        return new ContextContributor() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Kind kind() {
                return kind;
            }

            @Override
            public Duration timeBudget() {
                return budget;
            }

            @Override
            public ContextContribution contribute(ContextRequest request) {
                try {
                    return body.apply(request);
                } catch (RuntimeException ex) {
                    throw ex;
                } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
            }
        };
    }

    private static ContextContribution contribution(String contributor, String content, String... warnings) {
        Function<String, ContextSection> section = text -> new ContextSection("TEXT", TrustLevel.SERVER_AUTHORITATIVE,
                text.length(), false, text);
        return new ContextContribution(contributor, List.of(section.apply(content)), List.of(warnings));
    }
}
