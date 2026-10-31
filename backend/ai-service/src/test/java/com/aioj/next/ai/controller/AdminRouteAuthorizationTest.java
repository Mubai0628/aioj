package com.aioj.next.ai.controller;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan S9: a student calling an admin route directly (drafting, model settings, audits) is refused by
 * method security, so every handler under {@code /admin} or {@code /ai/admin} needs a teacher or an
 * admin, on the method or on its controller.
 */
class AdminRouteAuthorizationTest {
    private static final Set<String> STAFF_ONLY = Set.of("hasAnyRole('TEACHER','ADMIN')", "hasRole('ADMIN')");

    @Test
    void everyAdminRouteNeedsATeacherOrAnAdmin() throws Exception {
        List<String> adminRoutes = new ArrayList<>();
        for (Class<?> controller : controllers()) {
            for (Method method : controller.getDeclaredMethods()) {
                for (String path : paths(controller, method)) {
                    if (path.startsWith("/admin") || path.startsWith("/ai/admin")) {
                        assertThat(rule(controller, method)).as(controller.getSimpleName() + "." + method.getName()
                                + " " + path).isIn(STAFF_ONLY);
                        adminRoutes.add(path);
                    }
                }
            }
        }
        assertThat(adminRoutes).as("the scan found the admin routes")
                .contains("/admin/problem-drafts/{id}/approve", "/ai/admin/problem-draft-workspaces/{workspaceId}/turns",
                        "/admin/ai-model-configs/{scope}");
    }

    private static List<Class<?>> controllers() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<Class<?>> controllers = new ArrayList<>();
        for (var candidate : scanner.findCandidateComponents("com.aioj.next.ai.controller")) {
            controllers.add(Class.forName(candidate.getBeanClassName()));
        }
        return controllers;
    }

    /** Every path a handler answers on: its controller's prefixes times its own paths. */
    private static List<String> paths(Class<?> controller, Method method) {
        RequestMapping own = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
        if (own == null) {
            return List.of();
        }
        RequestMapping shared = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
        List<String> paths = new ArrayList<>();
        for (String prefix : shared == null || shared.path().length == 0 ? new String[]{""} : shared.path()) {
            for (String path : own.path().length == 0 ? new String[]{""} : own.path()) {
                paths.add(prefix + path);
            }
        }
        return paths;
    }

    private static String rule(Class<?> controller, Method method) {
        PreAuthorize rule = AnnotatedElementUtils.findMergedAnnotation(method, PreAuthorize.class);
        if (rule == null) {
            rule = AnnotatedElementUtils.findMergedAnnotation(controller, PreAuthorize.class);
        }
        return rule == null ? null : rule.value();
    }
}
