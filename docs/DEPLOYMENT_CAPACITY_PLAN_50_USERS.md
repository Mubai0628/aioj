# Single-node 50-user capacity plan

Status: `IMPLEMENTED_UNVERIFIED`
Last reviewed: 2026-08-11

The production design now colocates application, data, judge-worker, and
privileged Sandbox on one 4-core/8-GiB host. Results from the former split-node
topology cannot be inherited as single-node acceptance.

## Current limits

The image-only Compose caps aggregate container memory at approximately
6.25 GiB, leaving host/Docker reserve. CPU limits intentionally oversubscribe
four cores because authentication bursts and judge execution have different
expected peaks. Initial judge prefetch, concurrency, and max concurrency are
all one.

Historical evidence includes stable 50/50 login bursts with P95 about
5.80-6.26 seconds under the prior tested allocation, plus limited submission
bursts. This is useful regression evidence, not a single-node SLA.

## Required acceptance matrix

Run from an external client, never inside the production host:

1. 50 unique-account login burst, three rounds.
2. Authenticated problem/list/detail and contest/list/detail browsing mix.
3. Notification SSE connect, refresh/disconnect, and REST recovery.
4. Mixed 50-user submissions against representative C++ problems with judge
   concurrency one; measure queue drain rather than forcing unsafe parallelism.
5. Combined browsing/login/submission workload to expose shared-host contention.
6. Explicitly authorized AI-adjacent health/path tests; paid model calls remain
   a separate decision.

For every run collect success rate, HTTP P50/P95/P99/max, queue ready/unacked,
submission end-to-end and judge wall time, MySQL/Redis/RabbitMQ pressure,
container CPU/throttling/memory/restarts/OOM, host swap, load, disk growth, and
Sandbox errors. Abort on a real contest, pending jobs, nonempty pre-test queue,
swap activity, OOM/restart increase, or unexpected 5xx.

## Acceptance language

Do not claim a 50-user SLA until repeated mixed single-node runs meet the
agreed latency/error/queue targets without OOM, swap, restart, unsafe queue
growth, or unacceptable browser degradation. Record exact configuration and
test data scope so future results are comparable.

## Security/capacity coupling

Increasing Sandbox/judge concurrency also increases CPU, memory, PID, temp
disk, and privileged attack surface pressure. Capacity tuning never justifies
removing network, secret, mount, timeout, output, PID, memory, or log controls.
Any concurrency increase is a separate reviewed change with rollback evidence.
