package com.aioj.next.ai.architecture.fixture.caller;

import java.io.IOException;

/** Rule fixture: starts processes, which ai-service must never do. */
public class FixtureProcessStarter {
    Process withBuilder() throws IOException {
        return new ProcessBuilder("true").start();
    }

    Process withRuntime() throws IOException {
        return Runtime.getRuntime().exec(new String[]{"true"});
    }
}
