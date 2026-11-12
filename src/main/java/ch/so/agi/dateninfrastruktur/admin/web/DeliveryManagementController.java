package ch.so.agi.dateninfrastruktur.admin.web;

import ch.so.agi.dateninfrastruktur.admin.access.AuthorityService;
import ch.so.agi.dateninfrastruktur.admin.access.SubmitterAdministrationService;
import ch.so.agi.dateninfrastruktur.admin.delivery.DeliveryManagement;
import ch.so.agi.dateninfrastruktur.admin.security.VerifiedActorResolver;
import ch.so.agi.dateninfrastruktur.admin.web.UiPage.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Controller
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="di.web.enabled",havingValue="true",matchIfMissing=true)
@RequestMapping("/management/delivery")
public final class DeliveryManagementController {
    private final DeliveryManagement management;
    private final SubmitterAdministrationService submitters;
    private final AuthorityService authority;
    private final VerifiedActorResolver actors = new VerifiedActorResolver();

    public DeliveryManagementController(DeliveryManagement management, SubmitterAdministrationService submitters,
                                        AuthorityService authority) {
        this.management = management;
        this.submitters = submitters;
        this.authority = authority;
    }

    @GetMapping
    String index(Authentication auth, Model model, HttpServletRequest request) {
        var actor = actors.resolve(auth);
        var entries = new ArrayList<Entry>();
        for (var row : management.datasets(actor)) {
            var id = UUID.fromString(row.get("id").toString());
            var rights = authority.describe(actor, id);
            if (rights.submitters()) entries.add(new Entry(row.get("title").toString(), "/management/delivery/datasets/" + id, Map.of(), List.of()));
            if (rights.metadata()) entries.add(new Entry("CSV-Konfiguration: " + row.get("title"), "/management/delivery/datasets/" + id + "/configuration", Map.of(), List.of()));
        }
        return page(model, request, "Anlieferungen", entries, List.of());
    }

    @GetMapping("/datasets/{id}")
    String dataset(Authentication auth, @PathVariable UUID id, Model model, HttpServletRequest request) {
        var actor = actors.resolve(auth);
        String base = "/management/delivery/datasets/" + id;
        var entries = new ArrayList<Entry>();
        for (var row : management.assignments(actor, id)) {
            var revoke = new Form("Lieferrecht entziehen", base + "/revoke", List.of(
                field("assignmentId", "", "hidden", row.get("id")), field("reason", "Begründung", "textarea", "")), "Entziehen");
            entries.add(new Entry("Lieferrecht", null, row, row.get("revokedat") == null ? List.of(revoke) : List.of()));
        }
        for (var row : management.submissions(actor, id)) entries.add(new Entry("Submission", null, row, List.of()));
        var grant = new Form("Lieferrecht erteilen", base + "/grant", List.of(
            field("beneficiary", "Principal-ID", "text", ""), field("reason", "Begründung", "textarea", "")), "Erteilen");
        return page(model, request, "Lieferrechte und Submissions", entries, List.of(grant));
    }

    @PostMapping("/datasets/{id}/grant")
    String grant(Authentication auth, @PathVariable UUID id, @RequestParam UUID beneficiary, @RequestParam String reason) {
        submitters.grant(actors.resolve(auth), new SubmitterAdministrationService.Grant(
            new SubmitterAdministrationService.Scope(null, id), beneficiary, null, null, reason));
        return redirect(id);
    }

    @PostMapping("/datasets/{id}/revoke")
    String revoke(Authentication auth, @PathVariable UUID id, @RequestParam UUID assignmentId, @RequestParam String reason) {
        submitters.revoke(actors.resolve(auth), new SubmitterAdministrationService.Scope(null, id), assignmentId, reason);
        return redirect(id);
    }

    @GetMapping("/datasets/{id}/configuration")
    String configuration(Authentication auth, @PathVariable UUID id, Model model, HttpServletRequest request) {
        if (!authority.describe(actors.resolve(auth), id).metadata()) throw ch.so.agi.dateninfrastruktur.admin.access.BusinessException.forbidden();
        var form = new Form("CSV-Konfiguration", "/management/delivery/datasets/" + id + "/configuration", List.of(
            field("issue", "Ausgabe-ID", "text", ""), field("schema", "Schema-ID", "text", ""),
            new Field("modes", "Liefermodus", "select", "replace_only", List.of("replace_only", "append_only", "replace_and_append")),
            field("enabled", "Anlieferung aktiv", "checkbox", true)), "Speichern");
        var create = new Form("CSV-Schema anlegen", "/management/delivery/datasets/" + id + "/schema", List.of(
            field("columns", "Textspalten, durch Kommas getrennt", "text", ""),
            field("unique", "Gemeinsamer Schlüssel, durch Kommas getrennt", "text", ""),
            field("minimum", "Mindestanzahl Objekte (optional)", "number", "")), "Schema anlegen");
        return page(model, request, "CSV-Konfiguration", List.of(), List.of(form, create));
    }

    @PostMapping("/datasets/{id}/schema")
    String schema(Authentication auth, @PathVariable UUID id, @RequestParam String columns,
                  @RequestParam(defaultValue = "") String unique, @RequestParam(required = false) Integer minimum,
                  Model model, HttpServletRequest request) {
        UUID schema = management.createSchema(actors.resolve(auth), id, names(columns), names(unique), minimum);
        var entry = new Entry("CSV-Schema angelegt", null, Map.of("Schema-ID", schema), List.of());
        return page(model, request, "CSV-Konfiguration", List.of(entry), List.of());
    }

    private static List<String> names(String text) {
        if (text.length() > 4096) throw ch.so.agi.dateninfrastruktur.admin.access.BusinessException.invalid("Zu viele Spalten.");
        if (text.isBlank()) return List.of();
        return java.util.Arrays.stream(text.split(",", -1)).map(String::strip).toList();
    }

    @PostMapping("/datasets/{id}/configuration")
    String configure(Authentication auth, @PathVariable UUID id, @RequestParam UUID issue, @RequestParam UUID schema,
                     @RequestParam String modes, @RequestParam(defaultValue = "false") boolean enabled) {
        management.configure(actors.resolve(auth), id, issue, schema, enabled, modes);
        return "redirect:/management/delivery/datasets/" + id + "/configuration";
    }

    private static Field field(String name, String label, String type, Object value) { return new Field(name, label, type, value); }
    private static String redirect(UUID id) { return "redirect:/management/delivery/datasets/" + id; }
    private static String page(Model model, HttpServletRequest request, String title, List<Entry> entries, List<Form> forms) {
        model.addAttribute("page", new UiPage(title, "", entries, forms));
        model.addAttribute("section", NavigationSection.DELIVERY);
        model.addAttribute("csrf", request.getAttribute("_csrf"));
        return "page";
    }
}
