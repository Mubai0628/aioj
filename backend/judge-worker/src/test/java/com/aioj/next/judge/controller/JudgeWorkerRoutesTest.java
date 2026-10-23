package com.aioj.next.judge.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan A4.4: judge-worker serves formal judging only. The Agent execution and draft testcase
 * package endpoints moved to agent-executor in T1.10, so the judging sandbox never sees Agent
 * traffic; a new route here has to be added to this list on purpose.
 */
class JudgeWorkerRoutesTest {
    @Test
    void judgeWorkerServesOnlyItsJudgingRoutes() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        Set<String> routes = new TreeSet<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("com.aioj.next.judge")) {
            Class<?> controller = ClassUtils.forName(candidate.getBeanClassName(), getClass().getClassLoader());
            RequestMapping base = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
            String prefix = base == null || base.path().length == 0 ? "" : base.path()[0];
            for (Method method : controller.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) {
                    continue;
                }
                String path = mapping.path().length == 0 ? "" : mapping.path()[0];
                routes.add(Arrays.toString(mapping.method()) + " " + prefix + path);
            }
        }

        assertThat(routes).containsExactly("[GET] /api/v1/internal/judge/metrics");
        assertThat(routes).noneMatch(route -> route.contains("/internal/sandbox"));
    }
}
