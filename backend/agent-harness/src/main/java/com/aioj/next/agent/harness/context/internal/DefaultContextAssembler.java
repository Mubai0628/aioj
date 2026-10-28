package com.aioj.next.agent.harness.context.internal;

import com.aioj.next.agent.harness.context.api.AssembledContext;
import com.aioj.next.agent.harness.context.api.ContextAssembler;
import com.aioj.next.agent.harness.context.api.ContextContribution;
import com.aioj.next.agent.harness.context.api.ContextContributor;
import com.aioj.next.agent.harness.context.api.ContextPolicyException;
import com.aioj.next.agent.harness.context.api.ContextRequest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

/**
 * Runs contributors one at a time on a bounded pool, each within its own time budget. The
 * caller waits for at most that budget; a contributor that overruns is interrupted.
 */
public final class DefaultContextAssembler implements ContextAssembler {
    private final ThreadPoolExecutor pool;
    private final UnaryOperator<Runnable> taskDecorator;

    public DefaultContextAssembler(int threads, int queueSlots, UnaryOperator<Runnable> taskDecorator) {
        if (threads < 1 || queueSlots < 0) {
            throw new IllegalArgumentException("context pool needs at least one thread and no negative queue");
        }
        BlockingQueue<Runnable> queue = queueSlots == 0 ? new SynchronousQueue<>() : new ArrayBlockingQueue<>(queueSlots);
        AtomicInteger counter = new AtomicInteger();
        this.pool = new ThreadPoolExecutor(threads, threads, 60, TimeUnit.SECONDS, queue, runnable -> {
            Thread thread = new Thread(runnable, "harness-context-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        this.taskDecorator = taskDecorator == null ? UnaryOperator.identity() : taskDecorator;
    }

    @Override
    public AssembledContext assemble(ContextRequest request, List<ContextContributor> contributors) {
        List<ContextContribution> contributions = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (ContextContributor contributor : contributors == null ? List.<ContextContributor>of() : contributors) {
            Outcome outcome = run(contributor, request);
            if (outcome.contribution() != null) {
                contributions.add(outcome.contribution());
                warnings.addAll(outcome.contribution().warnings());
            } else if (contributor.kind() == ContextContributor.Kind.POLICY) {
                throw new ContextPolicyException(contributor.name(), outcome.reason(), outcome.cause());
            } else {
                warnings.add(DEGRADED_WARNING_PREFIX + contributor.name() + ":" + outcome.reason());
            }
        }
        return new AssembledContext(contributions, warnings);
    }

    private Outcome run(ContextContributor contributor, ContextRequest request) {
        Duration budget = contributor.timeBudget();
        if (budget == null || budget.isNegative() || budget.isZero()) {
            return Outcome.failed("error", new IllegalStateException("contributor " + contributor.name()
                    + " has no positive time budget"));
        }
        ContextRequest scoped = new ContextRequest(request.runId(), request.scenario(), request.userMessage(),
                request.attributes(), budget);
        FutureTask<ContextContribution> task = new FutureTask<>(() -> contributor.contribute(scoped));
        try {
            pool.execute(taskDecorator.apply(task));
        } catch (RejectedExecutionException ex) {
            return Outcome.failed("rejected", ex);
        }
        try {
            ContextContribution contribution = task.get(budget.toNanos(), TimeUnit.NANOSECONDS);
            return Outcome.ok(contribution == null ? ContextContribution.empty(contributor.name()) : contribution);
        } catch (TimeoutException ex) {
            task.cancel(true);
            return Outcome.failed("timeout", ex);
        } catch (ExecutionException ex) {
            return Outcome.failed("error", ex.getCause());
        } catch (InterruptedException ex) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            return Outcome.failed("interrupted", ex);
        }
    }

    @Override
    public void close() {
        pool.shutdownNow();
    }

    private record Outcome(ContextContribution contribution, String reason, Throwable cause) {
        static Outcome ok(ContextContribution contribution) {
            return new Outcome(contribution, null, null);
        }

        static Outcome failed(String reason, Throwable cause) {
            return new Outcome(null, reason, cause);
        }
    }
}
