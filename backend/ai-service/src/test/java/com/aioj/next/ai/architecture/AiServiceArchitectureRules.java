package com.aioj.next.ai.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import java.net.URLClassLoader;
import java.security.SecureClassLoader;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Rule builders for {@link AiServiceArchitectureTest}. The model-package rules
 * take the package root as a parameter, so the test can also prove them against
 * fixture classes that break them.
 */
final class AiServiceArchitectureRules {
    private AiServiceArchitectureRules() {
    }

    /** Plan §3.1 rule 1: no MyBatis, no agent-harness, no other ai-service package. */
    static ArchRule modelPackageIsSelfContained(String modelRoot) {
        return noClasses().that().resideInAPackage(modelRoot + "..")
                .should().dependOnClassesThat(resideInAnyPackage(
                                "com.baomidou..", "org.apache.ibatis..", "org.mybatis..", "com.aioj.next.agent.harness..")
                        .or(resideInAPackage("com.aioj.next.ai..").and(not(resideInAPackage(modelRoot + "..")))))
                .because("the model call package has no Maven boundary; it may use Spring and Jackson, "
                        + "but not persistence, the harness or the rest of ai-service (plan §3.1)")
                // The package arrives in T1.2b; the rule already holds, so it applies from its first commit.
                .allowEmptyShould(true);
    }

    /** Plan §3.1 rule 1: the rest of ai-service uses only the model package's api subpackage. */
    static ArchRule onlyTheModelApiIsUsedFromOutside(String modelRoot) {
        return noClasses().that().resideOutsideOfPackage(modelRoot + "..")
                .should().dependOnClassesThat(resideInAPackage(modelRoot + "..")
                        .and(not(resideInAPackage(modelRoot + ".api.."))))
                .because("ModelClient and its api types are the only way into the model call package (plan §3.2.1)");
    }

    /** Plan §3.1 rule 8: a package named internal is private to the package tree that contains it. */
    static ArchRule internalPackagesStayInsideTheirOwner() {
        return classes().should(new ArchCondition<JavaClass>("use internal packages only from the package tree that owns them") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                for (Dependency dependency : item.getDirectDependenciesFromSelf()) {
                    String target = dependency.getTargetClass().getPackageName();
                    int internal = target.indexOf(".internal");
                    if (internal < 0 || !(target.length() == internal + ".internal".length()
                            || target.charAt(internal + ".internal".length()) == '.')) {
                        continue;
                    }
                    String owner = target.substring(0, internal);
                    String source = item.getPackageName();
                    if (!source.equals(owner) && !source.startsWith(owner + ".")) {
                        events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                    }
                }
            }
        });
    }

    /** What running code in-process takes: processes, script engines, compilers, class loaders. */
    static final DescribedPredicate<JavaClass> IN_PROCESS_EXECUTION_TYPES =
            JavaClass.Predicates.belongToAnyOf(ProcessBuilder.class, URLClassLoader.class, SecureClassLoader.class)
                    .or(resideInAnyPackage("javax.script..", "javax.tools..", "jdk.jshell..",
                            "org.graalvm.polyglot..", "org.openjdk.nashorn..", "org.mozilla.javascript..",
                            "groovy..", "org.codehaus.groovy..", "org.codehaus.janino.."))
                    .as("process, script engine, compiler or class loader types");

    /**
     * Plan A4.1 (and §6.2): no user or model code runs in the ai-service process. It starts no
     * processes, evaluates no scripts, compiles nothing and defines no classes; code runs only in
     * agent-executor, behind the SandboxExecutor port.
     */
    static ArchRule noCodeRunsInProcess() {
        return noClasses().should().dependOnClassesThat(IN_PROCESS_EXECUTION_TYPES)
                .orShould().callMethodWhere(DescribedPredicate.describe("Runtime.exec or defineClass",
                        (JavaMethodCall call) -> (call.getTargetOwner().isEquivalentTo(Runtime.class)
                                && call.getName().equals("exec"))
                                || call.getName().equals("defineClass")
                                || call.getName().equals("defineHiddenClass")))
                .orShould().beAssignableTo(ClassLoader.class)
                .because("code runs in agent-executor, never in the ai-service process (plan A4.1)");
    }

    /** Outer classes with a direct dependency on the target, leaving out the excluded sources. */
    static Set<String> dependentsOf(JavaClasses classes, String target, Set<String> excludedSources) {
        Set<String> dependents = new TreeSet<>();
        for (JavaClass type : classes) {
            String source = outerName(type);
            if (source.equals(target) || excludedSources.contains(source)) {
                continue;
            }
            for (Dependency dependency : type.getDirectDependenciesFromSelf()) {
                if (target.equals(outerName(dependency.getTargetClass()))) {
                    dependents.add(source);
                }
            }
        }
        return dependents;
    }

    /** Outer classes that call a method matching the predicate. */
    static Set<String> callersOf(JavaClasses classes, Predicate<JavaMethodCall> call) {
        Set<String> callers = new TreeSet<>();
        for (JavaClass type : classes) {
            for (JavaMethodCall methodCall : type.getMethodCallsFromSelf()) {
                if (call.test(methodCall)) {
                    callers.add(outerName(type));
                }
            }
        }
        return callers;
    }

    /** Outer classes outside the package that classes in the package depend on, within the given scope. */
    static Set<String> externalTargetsOf(JavaClasses classes, String packageRoot, String scopeRoot) {
        Set<String> targets = new TreeSet<>();
        for (JavaClass type : classes) {
            if (!inPackage(type.getPackageName(), packageRoot)) {
                continue;
            }
            for (Dependency dependency : type.getDirectDependenciesFromSelf()) {
                JavaClass target = dependency.getTargetClass();
                if (inPackage(target.getPackageName(), scopeRoot) && !inPackage(target.getPackageName(), packageRoot)) {
                    targets.add(outerName(target));
                }
            }
        }
        return targets;
    }

    private static boolean inPackage(String packageName, String root) {
        return packageName.equals(root) || packageName.startsWith(root + ".");
    }

    private static String outerName(JavaClass type) {
        String name = type.getName();
        int nested = name.indexOf('$');
        return nested < 0 ? name : name.substring(0, nested);
    }
}
