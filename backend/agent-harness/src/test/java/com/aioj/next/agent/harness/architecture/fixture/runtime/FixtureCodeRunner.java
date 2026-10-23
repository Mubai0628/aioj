package com.aioj.next.agent.harness.architecture.fixture.runtime;

import javax.script.ScriptEngineManager;
import java.io.IOException;

/** Rule fixture: runs code in-process, which the Harness must never do (plan A4.1). */
public class FixtureCodeRunner extends ClassLoader {
    Process withBuilder(String command) throws IOException {
        return new ProcessBuilder(command).start();
    }

    Process withRuntime(String[] command) throws IOException {
        return Runtime.getRuntime().exec(command);
    }

    Object withScriptEngine(String source) throws Exception {
        return new ScriptEngineManager().getEngineByName("js").eval(source);
    }

    Class<?> withDefineClass(byte[] bytes) {
        return defineClass(null, bytes, 0, bytes.length);
    }
}
