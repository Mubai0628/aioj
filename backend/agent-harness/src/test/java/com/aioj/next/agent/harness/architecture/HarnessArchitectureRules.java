package com.aioj.next.agent.harness.architecture;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

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
                .orShould().dependOnClassesThat().resideInAPackage(base + ".context.internal..")
                .because("the loop uses tools and context, never the other way round");
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
