package ch.so.agi.dateninfrastruktur.admin.web;

import java.util.Locale;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import ch.so.agi.dateninfrastruktur.admin.access.AuthorityService;
import ch.so.agi.dateninfrastruktur.admin.access.BusinessException;
import ch.so.agi.dateninfrastruktur.admin.access.ManagementQueryService;
import ch.so.agi.dateninfrastruktur.admin.access.ManagementQueryService.DatasetDetail;
import ch.so.agi.dateninfrastruktur.admin.audit.ActorContext;
import ch.so.agi.dateninfrastruktur.admin.catalog.CatalogCommandService;
import ch.so.agi.dateninfrastruktur.admin.security.VerifiedActorResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;

@Controller
@ConditionalOnProperty(name = "di.web.enabled", havingValue = "true", matchIfMissing = true)
public final class DatasetLifecycleController {
    private static final String ACTION = DatasetController.DATASETS + "/{id}/{action:submit|publish|archive|access}";
    private final ManagementQueryService query;
    private final AuthorityService authority;
    private final CatalogCommandService catalog;

    public DatasetLifecycleController(ManagementQueryService query, AuthorityService authority, CatalogCommandService catalog) {
        this.query = query;
        this.authority = authority;
        this.catalog = catalog;
    }

    @GetMapping(ACTION)
    ModelAndView confirm(Authentication authentication, HttpServletRequest request, @PathVariable UUID id,
                         @PathVariable String action) {
        var actor = new VerifiedActorResolver().resolve(authentication);
        var operation = DatasetAction.valueOf(action.toUpperCase(Locale.ROOT));
        var dataset = allowedDataset(actor, id, operation);
        return confirmation(request, dataset, operation, UUID.randomUUID(),
            targetAccess(dataset, operation), "", null, false, 200);
    }

    @PostMapping(ACTION)
    ModelAndView apply(Authentication authentication, HttpServletRequest request, @PathVariable UUID id,
                       @PathVariable String action, @RequestParam UUID commandId,
                       @RequestParam(defaultValue = "") String reason, @RequestParam(defaultValue = "") String level) {
        var actor = new VerifiedActorResolver().resolve(authentication);
        var operation = DatasetAction.valueOf(action.toUpperCase(Locale.ROOT));
        boolean access = operation == DatasetAction.ACCESS;
        String validation = reason.isBlank() || reason.length() > 4000
            ? "Geben Sie eine Begründung mit höchstens 4000 Zeichen ein."
            : access && !level.equals("open") && !level.equals("restricted") ? "Der gewählte Datenzugang ist ungültig." : null;
        if (validation != null) {
            var dataset = allowedDataset(actor, id, operation);
            boolean stale = false;
            return confirmation(request, dataset, operation, commandId, targetAccess(dataset, operation),
                reason, stale ? "Der Datensatz wurde inzwischen geändert. Prüfen Sie den aktuellen Stand." : validation,
                stale, stale ? 409 : 400);
        }
        try {
            String justification = reason.strip();
            String commandCode = access ? "change_access_level" : operation.path();
            var command = DatasetController.command(request, commandId, commandCode, justification);
            if (access) {
                catalog.changeAccessLevel(actor, command, id, level, justification);
            } else {
                catalog.transition(actor, command, id, operation.path(), justification);
            }
            return DatasetController.redirectToDataset(id, "result=" + operation.path());

        } catch (BusinessException error) {
            if (error.status() != 400 && error.status() != 409) {
                throw error;
            }
            var dataset = query.detail(actor, id);
            boolean stale = !operation.allowed(dataset, authority.describe(actor, id));
            return confirmation(request, dataset, operation, commandId, targetAccess(dataset, operation),
                reason, error.getMessage(), stale, error.status());
        }
    }

    private DatasetDetail allowedDataset(ActorContext actor, UUID id, DatasetAction action) {
        var dataset = query.detail(actor, id);
        if (!action.allowed(dataset, authority.describe(actor, id))) {
            throw BusinessException.forbidden();
        }
        return dataset;
    }

    private static String targetAccess(DatasetDetail dataset, DatasetAction action) {
        if (action != DatasetAction.ACCESS) {
            return "";
        }
        return dataset.accessLevel().equals("open") ? "restricted" : "open";
    }

    private static ModelAndView confirmation(HttpServletRequest request, DatasetDetail dataset, DatasetAction action,
                                             UUID commandId, String targetAccess, String reason,
                                             String error, boolean stale, int status) {
        var view = new ModelAndView("dataset/confirm", HttpStatusCode.valueOf(status));
        view.addObject("confirmation", new ConfirmationView(dataset, action, commandId, targetAccess,
            reason, error, stale));
        view.addObject("csrf", request.getAttribute("_csrf"));
        return view;
    }

    public record ConfirmationView(DatasetDetail dataset, DatasetAction action, UUID commandId,
                                   String targetAccess, String reason, String error, boolean stale) {}
}
