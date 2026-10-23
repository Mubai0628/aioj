package com.aioj.next.sandbox.client;

import java.util.List;

/**
 * How a language is compiled and run in the sandbox. judge-worker had two
 * identical copies of this table, one for judging and one for the execution
 * endpoints; compile and run commands, file names and environment matched
 * item by item, so they became this one (plan T1.8).
 */
public record LanguageProfile(
        String language,
        boolean requiresCompile,
        List<String> compileArgs,
        String sourceFileName,
        String executableName,
        List<String> runArgs,
        List<String> envVars
) {
    private static final List<String> ENV = List.of("PATH=/usr/bin:/bin");

    public static LanguageProfile of(String language) {
        return switch (language) {
            case "cpp" -> new LanguageProfile("cpp", true,
                    List.of("/usr/bin/g++", "-O2", "-std=c++17", "main.cpp", "-o", "main"),
                    "main.cpp", "main",
                    List.of("./main"),
                    ENV);
            case "java" -> new LanguageProfile("java", true,
                    List.of("/usr/bin/javac", "Main.java"),
                    "Main.java", "Main.class",
                    List.of("/usr/bin/java", "-cp", ".", "Main"),
                    ENV);
            case "python" -> new LanguageProfile("python", false,
                    List.of(), "main.py", null,
                    List.of("/usr/bin/python3", "main.py"),
                    ENV);
            default -> throw new IllegalArgumentException("Unsupported language: " + language);
        };
    }
}
