package com.aioj.next.agent.harness.runtime.api;

@FunctionalInterface
public interface ModelStreamListener {
    void onTextDelta(String delta);
}
