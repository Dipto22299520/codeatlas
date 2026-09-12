package com.codeatlas.api;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.codeatlas.answers.AnswerModels;
import com.codeatlas.answers.AnswerServiceHandlers;
import com.codeatlas.answers.ServiceDefinition;
import com.codeatlas.answers.ServiceRegistry;
import com.codeatlas.security.Principal;
import com.codeatlas.security.ScopeService;

/**
 * REST access to the answer services. Discovery and invocation both read the
 * single service registry, so a newly registered service appears here without
 * editing this controller (BR-64).
 */
@RestController
@RequestMapping("/api/services")
public class ServiceController {

    private final ServiceRegistry registry;
    private final AnswerServiceHandlers handlers;
    private final ScopeService scopeService;
    private final AuditService audit;

    public ServiceController(ServiceRegistry registry, AnswerServiceHandlers handlers,
                             ScopeService scopeService, AuditService audit) {
        this.registry = registry;
        this.handlers = handlers;
        this.scopeService = scopeService;
        this.audit = audit;
    }

    /** Service discovery: the same definitions the assistant transport serves. */
    @GetMapping
    public List<Map<String, Object>> list() {
        return registry.all().stream().map(this::describe).toList();
    }

    private Map<String, Object> describe(ServiceDefinition definition) {
        return Map.of(
                "id", definition.id(),
                "description", definition.description(),
                "whenToUse", definition.whenToUse(),
                "distinction", definition.distinction(),
                "readOnly", definition.readOnly(),
                "requiredRole", definition.requiredRole(),
                "inputSchema", definition.inputSchema());
    }

    @PostMapping("/{serviceId}/invoke")
    public ResponseEntity<AnswerModels.Answer> invoke(@PathVariable String serviceId,
                                                      @RequestBody(required = false) Map<String, Object> input) {
        Principal principal = scopeService.currentPrincipal();
        if (!registry.exists(serviceId)) {
            audit.record(principal.username(), "rest", "invoke", serviceId, "unknown_service", null);
            return ResponseEntity.notFound().build();
        }
        long started = System.currentTimeMillis();
        AnswerModels.Answer answer = handlers.invoke(serviceId,
                input == null ? Map.of() : input, principal);
        audit.record(principal.username(), "rest", "invoke", serviceId,
                answer.status().wire() + " in " + (System.currentTimeMillis() - started) + "ms",
                answer.requestId());
        return ResponseEntity.ok(answer);
    }
}
