package com.aioj.next.ai.architecture.fixture.model.internal;

/** Rule fixture: an internal package that only the fake model package may use. */
public final class FixtureModelInternals {
    private FixtureModelInternals() {
    }

    // A method, not a constant: javac would inline a constant and hide the dependency.
    public static int retries() {
        return 2;
    }
}
