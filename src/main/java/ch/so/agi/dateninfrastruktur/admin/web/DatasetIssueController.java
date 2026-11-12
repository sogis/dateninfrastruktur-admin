package ch.so.agi.dateninfrastruktur.admin.web;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import ch.so.agi.dateninfrastruktur.admin.access.AuthorityService;
import ch.so.agi.dateninfrastruktur.admin.access.AuthorityService.Capabilities;
import ch.so.agi.dateninfrastruktur.admin.access.BusinessException;
import ch.so.agi.dateninfrastruktur.admin.access.ManagementQueryService;
import ch.so.agi.dateninfrastruktur.admin.access.ManagementQueryService.DatasetDetail;
import ch.so.agi.dateninfrastruktur.admin.access.ManagementQueryService.DatasetIssue;
import ch.so.agi.dateninfrastruktur.admin.access.ManagementQueryService.DatasetVersion;
import ch.so.agi.dateninfrastruktur.admin.identity.ActorContext;
import ch.so.agi.dateninfrastruktur.admin.catalog.DatasetIssueService;
import ch.so.agi.dateninfrastruktur.admin.security.VerifiedActorResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.RedirectView;

@Controller
@RequestMapping(DatasetController.DATASETS + "/{dataset}/issues")
@ConditionalOnProperty(name = "di.web.enabled", havingValue = "true", matchIfMissing = true)
public final class DatasetIssueController {
    private final ManagementQueryService query;
    private final AuthorityService authority;
    private final DatasetIssueService service;

    public DatasetIssueController(ManagementQueryService query, AuthorityService authority, DatasetIssueService service) {
        this.query = query;
        this.authority = authority;
        this.service = service;
    }

    @GetMapping
    String list(Authentication authentication, @PathVariable UUID dataset, Model model,
                @RequestParam(defaultValue = "") String result) {
        var actor = actor(authentication);
        var detail = query.detail(actor, dataset);
        var capabilities = authority.describe(actor, dataset);
        boolean canManage = canManage(detail, capabilities);
        var issues = query.issues(actor, dataset).stream()
            .map(issue -> new IssueRow(issue, canManage && canEdit(issue, capabilities), canManage && !issue.current()))
            .toList();
        String notice = switch (result) {
            case "created" -> "Die Ausgabe wurde erstellt.";
            case "saved" -> "Die Ausgabe wurde gespeichert.";
            case "current" -> "Die aktuelle Ausgabe wurde geändert.";
            default -> null;
        };
        model.addAttribute("overview", new IssueListView(detail, issues, canManage, notice));
        return "dataset/issues";
    }

    @GetMapping("/new")
    ModelAndView createForm(Authentication authentication, HttpServletRequest request, @PathVariable UUID dataset) {
        var detail = manageableDataset(actor(authentication), dataset);
        return form(request, detail, null, "", "", "", "", List.of(), UUID.randomUUID(), Map.of(), null, false, 200);
    }

    @PostMapping
    ModelAndView create(Authentication authentication, HttpServletRequest request, @PathVariable UUID dataset,
                        @RequestParam UUID commandId,
                        @RequestParam(defaultValue = "") String key, @RequestParam(defaultValue = "") String label) {
        var actor = actor(authentication);
        var errors = labelErrors(label);
        if (!key.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            errors.put("key", "Geben Sie einen gültigen Ausgabeschlüssel mit höchstens 128 Zeichen ein.");
        }
        if (!errors.isEmpty()) {
            return form(request, manageableDataset(actor, dataset), null, key, label, "", "", List.of(),
                commandId, errors, null, false, 400);
        }
        try {
            service.createIssue(actor, DatasetController.command(request, commandId, "create_issue", null),
                dataset, key, label.strip());
            return redirect(dataset, "created");

        } catch (BusinessException error) {
            if (!error.code().equals("DUPLICATE_ISSUE_KEY")) {
                throw error;
            }
            return form(request, manageableDataset(actor, dataset), null, key, label, "", "", List.of(),
                commandId, Map.of("key", error.getMessage()), null, false, 409);
        }
    }

    @GetMapping("/{issue}/edit")
    ModelAndView editForm(Authentication authentication, HttpServletRequest request, @PathVariable UUID dataset,
                         @PathVariable UUID issue) {
        var actor = actor(authentication);
        var detail = manageableDataset(actor, dataset);
        var selected = query.issue(actor, dataset, issue);
        var capabilities = authority.describe(actor, dataset);
        if (!canEdit(selected, capabilities)) {
            throw BusinessException.forbidden();
        }
        return form(request, detail, selected, selected.key(), selected.label(), selected.note(), selected.publicationStatus(),
            allowedStatuses(selected, capabilities), UUID.randomUUID(), Map.of(), null, false, 200);
    }

    @PostMapping("/{issue}/edit")
    ModelAndView update(Authentication authentication, HttpServletRequest request, @PathVariable UUID dataset,
                        @PathVariable UUID issue, @RequestParam UUID commandId,
                        @RequestParam(defaultValue = "") String label, @RequestParam(defaultValue = "") String note,
                        @RequestParam(defaultValue = "") String status) {
        var actor = actor(authentication);
        var errors = labelErrors(label);
        if (note.length() > 100_000) {
            errors.put("note", "Die Bemerkung darf höchstens 100 000 Zeichen enthalten.");
        }
        if (DatasetLabels.PUBLICATION_STATUSES.stream().noneMatch(choice -> choice.code().equals(status))) {
            errors.put("status", "Wählen Sie einen Publikationsstatus aus.");
        }
        if (!errors.isEmpty()) {
            var detail = manageableDataset(actor, dataset);
            var selected = query.issue(actor, dataset, issue);
            var capabilities = authority.describe(actor, dataset);
            if (!canEdit(selected, capabilities)) {
                throw BusinessException.forbidden();
            }
            return form(request, detail, selected, selected.key(), label, note, status, allowedStatuses(selected, capabilities),
                commandId, errors, null, false, 400);
        }
        service.updateIssueMetadata(actor, DatasetController.command(request, commandId, "update_issue", null),
                dataset, issue, label.strip(), note.isBlank() ? null : note, status);
            return redirect(dataset, "saved");

    }

    @GetMapping("/{issue}/current")
    ModelAndView currentForm(Authentication authentication, HttpServletRequest request, @PathVariable UUID dataset,
                            @PathVariable UUID issue) {
        var actor = actor(authentication);
        var detail = manageableDataset(actor, dataset);
        var selected = query.issue(actor, dataset, issue);
        if (selected.current()) {
            throw BusinessException.conflict("Diese Ausgabe ist bereits aktuell.");
        }
        return currentView(request, detail, selected, UUID.randomUUID(), null, false, 200);
    }

    @PostMapping("/{issue}/current")
    ModelAndView makeCurrent(Authentication authentication, HttpServletRequest request, @PathVariable UUID dataset,
                             @PathVariable UUID issue, @RequestParam UUID commandId) {
        var actor = actor(authentication);
        service.setCurrentIssue(actor, DatasetController.command(request, commandId, "set_current_issue", null),
                dataset, issue);
            return redirect(dataset, "current");

    }

    @GetMapping("/{issue}/versions")
    String versions(Authentication authentication, @PathVariable UUID dataset, @PathVariable UUID issue, Model model) {
        var actor = actor(authentication);
        var detail = query.detail(actor, dataset);
        var versions = query.versions(actor, dataset, issue);
        model.addAttribute("versionOverview", new VersionView(detail, versions.issue(), versions.versions()));
        return "dataset/versions";
    }

    private DatasetDetail manageableDataset(ActorContext actor, UUID id) {
        var detail = query.detail(actor, id);
        if (!detail.kind().equals("series")) {
            throw BusinessException.conflict("Einzelangebote behalten ihre interne Standardausgabe.");
        }
        if (!canManage(detail, authority.describe(actor, id))) {
            throw BusinessException.forbidden();
        }
        return detail;
    }

    private static boolean canManage(DatasetDetail detail, Capabilities capabilities) {
        return detail.kind().equals("series") && capabilities.metadata()
            && (!detail.publicationStatus().equals("published") || capabilities.publish());
    }

    private static boolean canEdit(DatasetIssue issue, Capabilities capabilities) {
        return capabilities.publish() || (!issue.publicationStatus().equals("published") && !issue.publicationStatus().equals("archived"));
    }

    private static List<DatasetLabels.Choice> allowedStatuses(DatasetIssue issue, Capabilities capabilities) {
        return DatasetLabels.PUBLICATION_STATUSES.stream().filter(choice -> {
            String current = issue.publicationStatus();
            String target = choice.code();
            if (current.equals("archived")) {
                return target.equals("archived");
            }
            if (current.equals("published")) {
                return target.equals("published") || target.equals("archived");
            }
            return capabilities.publish() || target.equals("draft") || target.equals("in_review");
        }).toList();
    }

    private static Map<String, String> labelErrors(String label) {
        var errors = new LinkedHashMap<String, String>();
        if (label.isBlank() || label.length() > 255) {
            errors.put("label", "Geben Sie eine Bezeichnung mit höchstens 255 Zeichen ein.");
        }
        return errors;
    }

    private static String staleMessage() {
        return "Der Datensatz wurde inzwischen geändert. Laden Sie den aktuellen Stand.";
    }

    private static ModelAndView form(HttpServletRequest request, DatasetDetail dataset, DatasetIssue issue,
                                     String key, String label, String note, String status, List<DatasetLabels.Choice> statuses,
                                     UUID commandId, Map<String, String> errors, String message,
                                     boolean stale, int httpStatus) {
        var view = new ModelAndView("dataset/issue-form", HttpStatusCode.valueOf(httpStatus));
        view.addObject("issueForm", new IssueFormView(dataset, issue, key, label, note == null ? "" : note, status, statuses,
            commandId, Collections.unmodifiableMap(new LinkedHashMap<>(errors)), message, stale));
        view.addObject("csrf", request.getAttribute("_csrf"));
        return view;
    }

    private static ModelAndView currentView(HttpServletRequest request, DatasetDetail dataset, DatasetIssue issue,
                                            UUID commandId, String message, boolean stale, int status) {
        var view = new ModelAndView("dataset/issue-current", HttpStatusCode.valueOf(status));
        view.addObject("currentForm", new IssueCurrentView(dataset, issue, commandId, message, stale));
        view.addObject("csrf", request.getAttribute("_csrf"));
        return view;
    }

    private static ModelAndView redirect(UUID dataset, String result) {
        var redirect = new RedirectView(DatasetController.DATASETS + "/" + dataset + "/issues?result=" + result, true);
        redirect.setStatusCode(HttpStatus.SEE_OTHER);
        redirect.setExposeModelAttributes(false);
        return new ModelAndView(redirect);
    }

    private static ActorContext actor(Authentication authentication) {
        return new VerifiedActorResolver().resolve(authentication);
    }

    public record IssueListView(DatasetDetail dataset, List<IssueRow> issues, boolean canCreate, String notice) {}
    public record IssueRow(DatasetIssue issue, boolean canEdit, boolean canMakeCurrent) {}
    public record IssueFormView(DatasetDetail dataset, DatasetIssue issue, String key, String label, String note,
                                String status, List<DatasetLabels.Choice> statuses, UUID commandId,
                                Map<String, String> errors, String message, boolean stale) {}
    public record IssueCurrentView(DatasetDetail dataset, DatasetIssue issue, UUID commandId,
                                   String message, boolean stale) {}
    public record VersionView(DatasetDetail dataset, DatasetIssue issue, List<DatasetVersion> versions) {}
}
