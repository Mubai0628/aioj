package com.aioj.next.problem.controller;

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
 * Plan S9: the filter chain lets everyone through {@code /problems/**} (the public catalog) and leaves
 * the rest to method security, so a handler is only as closed as its annotation. Admin routes need a
 * teacher or an admin; under {@code /problems} only the two catalog reads go without a rule.
 */
class RouteAuthorizationTest {
    private static final Set<String> STAFF_ONLY = Set.of("hasAnyRole('TEACHER','ADMIN')", "hasRole('ADMIN')");
    private static final Set<String> CATALOG_READS = Set.of("ProblemController.list", "ProblemController.get");

    @Test
    void adminRoutesNeedStaffAndOpenRoutesNeedARule() throws Exception {
        List<String> admin = new ArrayList<>();
        List<String> open = new ArrayList<>();
        for (Class<?> controller : controllers()) {
            for (Method method : controller.getDeclaredMethods()) {
                String handler = controller.getSimpleName() + "." + method.getName();
                for (String path : paths(controller, method)) {
                    if (path.startsWith("/admin")) {
                        assertThat(rule(controller, method)).as(handler + " " + path).isIn(STAFF_ONLY);
                        admin.add(path);
                    } else if (path.startsWith("/problems") && !CATALOG_READS.contains(handler)) {
                        assertThat(rule(controller, method)).as(handler + " " + path + ", open at the filter chain")
                                .isNotNull();
                        open.add(path);
                    }
                }
            }
        }
        assertThat(admin).as("the scan found the admin routes").contains("/admin/judge/metrics");
        assertThat(open).as("the scan found the open routes")
                .contains("/problems/{id}/standard-solution", "/problems/{problemId}/testcase-packages/{packageId}/download");
    }

    private static List<Class<?>> controllers() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<Class<?>> controllers = new ArrayList<>();
        for (var candidate : scanner.findCandidateComponents("com.aioj.next.problem.controller")) {
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
