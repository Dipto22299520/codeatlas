package com.example.approval;

import java.lang.reflect.Method;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Sends escalation notices through a handler chosen at runtime.
 *
 * The handler class name comes from configuration and is dispatched
 * reflectively, so the concrete target cannot be determined by static
 * analysis. This is intentional: the platform must report it as an
 * unresolved dynamic relationship rather than guessing a target.
 */
@Component
public class AuditNotifier {

    @Value("${audit.notifier.handler-class}")
    private String handlerClassName;

    public void notifyEscalation(String requestId) {
        try {
            Class<?> handlerClass = Class.forName(handlerClassName);
            Object handler = handlerClass.getDeclaredConstructor().newInstance();
            Method method = handlerClass.getMethod("handle", String.class);
            method.invoke(handler, requestId);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Escalation notification failed", e);
        }
    }
}
