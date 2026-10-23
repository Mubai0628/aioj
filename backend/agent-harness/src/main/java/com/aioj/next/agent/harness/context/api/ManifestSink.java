package com.aioj.next.agent.harness.context.api;

/** Where manifests go. The sink handles its own write failures; they never block the run. */
@FunctionalInterface
public interface ManifestSink {
    ManifestSink NONE = manifest -> {
    };

    void record(ModelCallManifest manifest);
}
