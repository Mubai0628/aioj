package com.aioj.next.ai.architecture.fixture.caller;

import javax.script.ScriptEngineManager;
import javax.tools.ToolProvider;
import java.net.URL;
import java.net.URLClassLoader;

/** Rule fixture: evaluates, compiles and loads code in-process, which ai-service must never do. */
public class FixtureCodeLoader extends ClassLoader {
    Object withScriptEngine(String source) throws Exception {
        return new ScriptEngineManager().getEngineByName("js").eval(source);
    }

    Object withCompiler() {
        return ToolProvider.getSystemJavaCompiler();
    }

    Class<?> withDefineClass(byte[] bytes) {
        return defineClass(null, bytes, 0, bytes.length);
    }

    ClassLoader withUrlClassLoader(URL[] urls) {
        return new URLClassLoader(urls);
    }
}
