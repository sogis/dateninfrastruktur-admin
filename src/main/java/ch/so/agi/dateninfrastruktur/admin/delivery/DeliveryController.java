package ch.so.agi.dateninfrastruktur.admin.delivery;

import ch.so.agi.dateninfrastruktur.admin.access.BusinessException;
import ch.so.agi.dateninfrastruktur.admin.audit.ActorContext;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="di.web.enabled",havingValue="true",matchIfMissing=true)
@RequestMapping("/internal/deliveries")
public final class DeliveryController {
    private final DeliveryService service;

    public DeliveryController(DeliveryService service) { this.service = service; }

    private static ActorContext actor(JwtAuthenticationToken token) {
        if (token == null || !token.isAuthenticated()) throw new BusinessException(401, "AUTHENTICATION_REQUIRED", "Bitte anmelden.");
        return new ActorContext(token.getToken().getIssuer().toString(), token.getToken().getSubject(), "user", null);
    }

    @GetMapping("/eligible")
    List<DeliveryService.Eligible> eligible(JwtAuthenticationToken token, @RequestParam(defaultValue = "") String search,
                                          @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "30") int limit) {
        return service.eligible(actor(token), search, offset, limit);
    }

    @PutMapping("/{id}")
    DeliveryService.State create(JwtAuthenticationToken token, @PathVariable UUID id, @RequestBody DeliveryService.Create input) {
        return service.create(actor(token), id, input);
    }

    @GetMapping("/{id}")
    DeliveryService.State get(JwtAuthenticationToken token, @PathVariable UUID id) { return service.get(actor(token), id); }

    @PostMapping("/{id}/cancel")
    DeliveryService.State cancel(JwtAuthenticationToken token, @PathVariable UUID id) { return service.cancel(actor(token), id); }

    @PostMapping("/{id}/start")
    DeliveryService.State start(JwtAuthenticationToken token, @PathVariable UUID id) { return service.start(actor(token), id); }

    @PostMapping("/{id}/complete")
    DeliveryService.State complete(JwtAuthenticationToken token, @PathVariable UUID id, @RequestBody DeliveryService.Complete input) {
        return service.complete(actor(token), id, input);
    }

    public record Finish(String message) {}

    @PostMapping("/{id}/reject")
    DeliveryService.State reject(JwtAuthenticationToken token, @PathVariable UUID id, @RequestBody Finish input) {
        return service.finish(actor(token), id, "rejected", input.message());
    }

    @PostMapping("/{id}/fail")
    DeliveryService.State fail(JwtAuthenticationToken token, @PathVariable UUID id, @RequestBody Finish input) {
        return service.finish(actor(token), id, "failed", input.message());
    }

    @ExceptionHandler(BusinessException.class)
    org.springframework.http.ResponseEntity<Map<String, String>> failure(BusinessException error) {
        return org.springframework.http.ResponseEntity.status(error.status())
            .body(Map.of("code", error.code(), "detail", error.getMessage()));
    }

    @ExceptionHandler(org.apache.cayenne.CayenneRuntimeException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    Map<String, String> databaseUnavailable() { return Map.of("code", "DEPENDENCY_UNAVAILABLE"); }
}
