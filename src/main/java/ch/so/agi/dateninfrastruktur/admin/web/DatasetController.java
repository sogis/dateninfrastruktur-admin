package ch.so.agi.dateninfrastruktur.admin.web;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;

import ch.so.agi.dateninfrastruktur.admin.access.BusinessException;
import ch.so.agi.dateninfrastruktur.admin.access.ManagementQueryService;
import ch.so.agi.dateninfrastruktur.admin.access.ManagementQueryService.DatasetFilters;
import ch.so.agi.dateninfrastruktur.admin.access.ManagementQueryService.DatasetOffice;
import ch.so.agi.dateninfrastruktur.admin.access.ManagementQueryService.DatasetRow;
import ch.so.agi.dateninfrastruktur.admin.audit.ActorContext;
import ch.so.agi.dateninfrastruktur.admin.audit.CommandContext;
import ch.so.agi.dateninfrastruktur.admin.catalog.CatalogCommandService;
import ch.so.agi.dateninfrastruktur.admin.security.RequestCorrelationFilter;
import ch.so.agi.dateninfrastruktur.admin.security.VerifiedActorResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.RedirectView;
import org.springframework.web.util.UriComponentsBuilder;

@Controller
@ConditionalOnProperty(name = "di.web.enabled", havingValue = "true", matchIfMissing = true)
public final class DatasetController {
    private static final String DATASETS = "/management/datasets";

    private final ManagementQueryService query;
    private final CatalogCommandService catalog;

    public DatasetController(ManagementQueryService query, CatalogCommandService catalog) {
        this.query = query;
        this.catalog = catalog;
    }

    @GetMapping({"/management", DATASETS})
    String list(Authentication authentication, Model model,
                @RequestParam(defaultValue = "") String search,
                @RequestParam(defaultValue = "") String publicationStatus,
                @RequestParam(defaultValue = "") String accessLevel,
                @RequestParam(required = false) UUID ownerOffice,
                @RequestParam(defaultValue = "0") int page) {
        var filters = new DatasetFilters(search, publicationStatus, accessLevel, ownerOffice, page);
        var result = query.datasets(new VerifiedActorResolver().resolve(authentication), filters);
        var rows = result.rows().stream()
            .map(row -> new RowLink(row, datasetUri(DATASETS + "/" + row.id(), filters, filters.page())))
            .toList();
        var previous = result.hasPrevious() ? datasetUri(DATASETS, filters, filters.page() - 1) : null;
        var next = result.hasNext() ? datasetUri(DATASETS, filters, filters.page() + 1) : null;
        var first = rows.isEmpty() && result.hasPrevious() ? datasetUri(DATASETS, filters, 0) : null;
        model.addAttribute("list", new ListView(filters, rows, result.offices(), result.hasDatasets(),
            result.canCreate(), first, previous, next));
        return "dataset/list";
    }

    @GetMapping(DATASETS + "/new")
    ModelAndView createForm(Authentication authentication, HttpServletRequest request) {
        var actor = new VerifiedActorResolver().resolve(authentication);
        return creationView(request, actor, UUID.randomUUID(), "", "", "",
            DatasetMetadataForm.empty(), Map.of(), null, HttpStatus.OK.value());
    }

    @PostMapping(DATASETS)
    ModelAndView create(Authentication authentication, HttpServletRequest request,
                        @RequestParam(defaultValue = "") String commandId,
                        @RequestParam(defaultValue = "") String ownerOffice,
                        @RequestParam(defaultValue = "") String identifier,
                        @RequestParam(defaultValue = "") String kind,
                        @ModelAttribute DatasetMetadataForm metadata) {
        var actor = new VerifiedActorResolver().resolve(authentication);
        var errors = new LinkedHashMap<String, String>();
        var command = parseUuid(commandId);
        var office = parseUuid(ownerOffice);
        String selectedOffice = office == null ? ownerOffice : office.toString();
        if (office == null) {
            errors.put("ownerOffice", "Wählen Sie ein zuständiges Amt aus.");
        }
        if (!identifier.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,254}")) {
            errors.put("identifier", "Die Kennung muss mit einem Buchstaben oder einer Ziffer beginnen.");
        }
        if (!kind.equals("standalone") && !kind.equals("series")) {
            errors.put("kind", "Wählen Sie einen Angebotstyp aus.");
        }
        errors.putAll(metadata.errors());
        if (command == null || !errors.isEmpty()) {
            return creationView(request, actor, command == null ? UUID.randomUUID() : command,
                selectedOffice, identifier, kind, metadata, errors,
                command == null ? "Die Formular-ID war ungültig. Prüfen Sie die Angaben und senden Sie erneut." : null,
                HttpStatus.BAD_REQUEST.value());
        }
        try {
            UUID id = catalog.createDraft(actor, command(request, command, "create_draft", null),
                new CatalogCommandService.Create(office, identifier, kind, metadata.metadata()));
            var redirect = new RedirectView(DATASETS + "/" + id + "?created=true", true);
            redirect.setStatusCode(HttpStatus.SEE_OTHER);
            redirect.setExposeModelAttributes(false);
            return new ModelAndView(redirect);
        } catch (BusinessException error) {
            if (error.code().equals("DUPLICATE_DATASET_IDENTIFIER")) {
                errors.put("identifier", error.getMessage());
            }
            if (error.status() == 403) {
                errors.put("ownerOffice", "Sie dürfen für dieses Amt keinen Datensatz erstellen.");
            }
            return creationView(request, actor, command, selectedOffice, identifier, kind, metadata, errors,
                errors.isEmpty() ? error.getMessage() : null, error.status());
        }
    }

    private ModelAndView creationView(HttpServletRequest request, ActorContext actor, UUID commandId,
                                      String ownerOffice, String identifier, String kind, DatasetMetadataForm metadata,
                                      Map<String, String> errors, String message, int status) {
        var offices = query.eligibleOwnerOffices(actor);
        var view = new ModelAndView("dataset/new", HttpStatusCode.valueOf(status));
        view.addObject("creation", new CreationView(commandId, ownerOffice, identifier, kind, metadata,
            offices, Collections.unmodifiableMap(new LinkedHashMap<>(errors)), message));
        view.addObject("csrf", request.getAttribute("_csrf"));
        return view;
    }

    private static UUID parseUuid(String input) {
        try {
            var id = UUID.fromString(input);
            return id.toString().equalsIgnoreCase(input) ? id : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static CommandContext command(HttpServletRequest request, UUID id, String action, String reason) {
        return new CommandContext(id, (UUID) request.getAttribute(RequestCorrelationFilter.ATTRIBUTE), action, reason);
    }

    public record CreationView(UUID commandId, String ownerOffice, String identifier, String kind,
                               DatasetMetadataForm metadata, List<DatasetOffice> offices,
                               Map<String, String> errors, String message) {}

    private static String datasetUri(String path, DatasetFilters filters, int page) {
        var builder = UriComponentsBuilder.fromPath(path);
        var values = new LinkedHashMap<String, Object>();
        queryParameter(builder, values, "search", filters.search());
        queryParameter(builder, values, "publicationStatus", filters.publicationStatus());
        queryParameter(builder, values, "accessLevel", filters.accessLevel());
        queryParameter(builder, values, "ownerOffice", filters.ownerOffice());
        queryParameter(builder, values, "page", page == 0 ? null : page);
        return builder.encode().buildAndExpand(values).toUriString();
    }

    private static void queryParameter(UriComponentsBuilder builder, Map<String, Object> values,
                                       String name, Object value) {
        if (value != null && !value.toString().isBlank()) {
            builder.queryParam(name, "{" + name + "}");
            values.put(name, value);
        }
    }

    public record ListView(DatasetFilters filters, List<RowLink> rows, List<DatasetOffice> offices,
                           boolean hasDatasets, boolean canCreate,
                           String firstHref, String previousHref, String nextHref) {}

    public record RowLink(DatasetRow dataset, String href) {}
}
