package com.query.insight.security;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.query.insight.auth.AuthController;
import com.query.insight.analysis.AiAnalysisController;
import com.query.insight.dashboard.DashboardController;
import com.query.insight.employee.EmployeeController;
import com.query.insight.evaluation.EvaluationController;
import com.query.insight.notification.NotificationController;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;

class AuthenticationPrincipalAnnotationTests {

    @Test
    void jwtControllerParametersAreResolvedFromTheAuthenticatedPrincipal() {
        List<Class<?>> controllers = List.of(AuthController.class, AiAnalysisController.class, DashboardController.class,
                EmployeeController.class, EvaluationController.class, NotificationController.class);

        for (Class<?> controller : controllers) {
            for (Method method : controller.getDeclaredMethods()) {
                if (Modifier.isPrivate(method.getModifiers())) {
                    continue;
                }
                for (Parameter parameter : method.getParameters()) {
                    if (parameter.getType().equals(Jwt.class)) {
                        assertTrue(parameter.isAnnotationPresent(AuthenticationPrincipal.class),
                                () -> controller.getSimpleName() + "." + method.getName()
                                        + " must resolve Jwt with @AuthenticationPrincipal");
                    }
                }
            }
        }
    }
}
