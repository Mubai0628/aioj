package com.aioj.next.ai.architecture.fixture.model.api;

/** Rule fixture: the public face of a fake model package. */
public interface FixtureModelClient {
    String complete(String prompt);
}
