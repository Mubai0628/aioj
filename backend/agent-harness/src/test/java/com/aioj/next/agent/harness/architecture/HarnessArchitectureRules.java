package com.aioj.next.agent.harness.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** Rules for agent-harness, parameterized by base package so fixtures can prove they fire. */
final class HarnessArchitectureRules {
    /** Words of the online-judge domain (A2.3); the Harness must stay free of them. */
    static final Pattern DOMAIN_WORDS = Pattern.compile(
            "(?i)\\b(draft|contest|problem|submission|judge|tutor|testcase|postmortem|plagiarism|classroom)\\w*");

    private HarnessArchitectureRules() {
    }

    /** D2: the Harness runs on the JDK and Jackson only. */
    static ArchRule onlyJdkAndJackson(String base) {
        return classes().that().resideInAPackage(base + "..")
                .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "tools.jackson..", base + "..")
                .because("agent-harness may depend on the JDK and Jackson only (plan D2)");
    }

    /** An {@code X.internal} package is used only inside {@code X}. */
    static ArchRule internalStaysInsideItsSubpackage(String base) {
        return classes().that().resideInAPackage(base + "..internal..")
                .should(new ArchCondition<>("be used only inside their own subpackage") {
                    @Override
                    public void check(JavaClass internal, ConditionEvents events) {
                        String name = internal.getPackageName();
                        String owner = name.substring(0, name.indexOf(".internal"));
                        for (Dependency dependency : internal.getDirectDependenciesToSelf()) {
                            String origin = dependency.getOriginClass().getPackageName();
                            if (!origin.equals(owner) && !origin.startsWith(owner + ".")) {
                                events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                            }
                        }
                    }
                });
    }

    /** runtime → tool → context: the lower layers never reach up. */
    static ArchRule layersPointOneWay(String base) {
        return noClasses().that().resideInAnyPackage(base + ".tool..", base + ".context..")
                .should().dependOnClassesThat().resideInAPackage(base + ".runtime..")
                .because("the loop uses tools and context, never the other way round");
    }

    /** The eight submodules of plan A3.1; each has an {@code api} and, where it needs one, an {@code internal}. */
    static final List<String> SUBMODULES = List.of(
            "runtime", "tool", "context", "memory", "profile", "channel", "sandbox", "audit");

    /** A3.1: every class belongs to a submodule's {@code api} or {@code internal} package. */
    static ArchRule everyClassBelongsToASubmodule(String base) {
        String[] packages = SUBMODULES.stream()
                .flatMap(name -> Stream.of(base + "." + name + ".api..", base + "." + name + ".internal.."))
                .toArray(String[]::new);
        return classes().that().resideInAPackage(base + "..")
                .should().resideInAnyPackage(packages)
                .because("plan A3.1 splits the Harness into eight submodules with an api each");
    }

    /** A3.1: one submodule reaches another only through that submodule's {@code api} package. */
    static ArchRule submodulesMeetThroughTheirApi(String base) {
        return classes().that().resideInAPackage(base + "..")
                .should(new ArchCondition<>("depend on other submodules through their api package only") {
                    @Override
                    public void check(JavaClass origin, ConditionEvents events) {
                        String own = submoduleOf(base, origin.getPackageName());
                        for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                            String target = dependency.getTargetClass().getPackageName();
                            String other = submoduleOf(base, target);
                            if (other != null && !other.equals(own) && !target.equals(base + "." + other + ".api")
                                    && !target.startsWith(base + "." + other + ".api.")) {
                                events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                            }
                        }
                    }
                });
    }

    /** The submodule a package belongs to ({@code runtime} for {@code base.runtime.api}), or null outside base. */
    private static String submoduleOf(String base, String packageName) {
        if (!packageName.startsWith(base + ".")) {
            return null;
        }
        String rest = packageName.substring(base.length() + 1);
        int dot = rest.indexOf('.');
        return dot < 0 ? rest : rest.substring(0, dot);
    }

    /**
     * Plan A4.1: the Harness runs no user or model code itself. No processes, script engines,
     * compilers or class loading; execution tools go through SandboxExecutor to agent-executor.
     */
    static ArchRule runsNoCodeInProcess(String base) {
        return noClasses().that().resideInAPackage(base + "..")
                .should().dependOnClassesThat(JavaClass.Predicates.belongToAnyOf(
                                ProcessBuilder.class, URLClassLoader.class, SecureClassLoader.class)
                        .or(resideInAnyPackage("javax.script..", "javax.tools..", "jdk.jshell.."))
                        .as("process, script engine, compiler or class loader types"))
                .orShould().callMethodWhere(DescribedPredicate.describe("Runtime.exec or defineClass",
                        (JavaMethodCall call) -> (call.getTargetOwner().isEquivalentTo(Runtime.class)
                                && call.getName().equals("exec"))
                                || call.getName().equals("defineClass")
                                || call.getName().equals("defineHiddenClass")))
                .orShould().beAssignableTo(ClassLoader.class)
                .because("execution tools run code through SandboxExecutor only (plan A4.1)");
    }

    static ArchRule contextDoesNotKnowTools(String base) {
        return noClasses().that().resideInAPackage(base + ".context..")
                .should().dependOnClassesThat().resideInAPackage(base + ".tool..");
    }

    /** Every {@code file:line: word} hit of {@link #DOMAIN_WORDS} below {@code sourceRoot}. */
    static List<String> domainWordHits(Path sourceRoot) {
        List<String> hits = new ArrayList<>();
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    Matcher matcher = DOMAIN_WORDS.matcher(lines.get(i));
                    while (matcher.find()) {
                        hits.add(sourceRoot.relativize(file) + ":" + (i + 1) + ": " + matcher.group());
                    }
                }
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return hits;
    }
}
