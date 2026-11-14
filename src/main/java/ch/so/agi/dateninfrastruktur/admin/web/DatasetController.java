package ch.so.agi.dateninfrastruktur.admin.web;

import java.util.Arrays;
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
import ch.so.agi.dateninfrastruktur.admin.access.ManagementQueryService.DatasetFilters;
import ch.so.agi.dateninfrastruktur.admin.access.ManagementQueryService.DatasetOffice;
import ch.so.agi.dateninfrastruktur.admin.access.ManagementQueryService.DatasetRow;
import ch.so.agi.dateninfrastruktur.admin.identity.ActorContext;
import ch.so.agi.dateninfrastruktur.admin.command.CommandContext;
import ch.so.agi.dateninfrastruktur.admin.catalog.CatalogCommandService;
import ch.so.agi.dateninfrastruktur.admin.model.Office;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.RedirectView;
import org.springframework.web.util.UriComponentsBuilder;

@Controller
@ConditionalOnProperty(name = "di.web.enabled", havingValue = "true", matchIfMissing = true)
public final class DatasetController {
    static final String DATASETS = "/management/datasets";

    private final ManagementQueryService query;
    private final CatalogCommandService catalog;
    private final AuthorityService authority;

    public DatasetController(ManagementQueryService query, CatalogCommandService catalog, AuthorityService authority) {
        this.query = query;
        this.catalog = catalog;
        this.authority = authority;
    }

    @GetMapping({"/management", DATASETS})
    String list(Authentication authentication, Model model,
                @RequestParam(defaultValue = "") String search,
                @RequestParam(defaultValue = "") String publicationStatus,
                @RequestParam(defaultValue = "") String accessLevel,
                @RequestParam(required = false) String ownerOffice,
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
        String office = ownerOffice.isBlank() || ownerOffice.codePointCount(0, ownerOffice.length()) > Office.MAX_TID_LENGTH ? null : ownerOffice;
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
            return redirectToDataset(id, "created=true");
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

    static CommandContext command(HttpServletRequest request, UUID id, String action, String reason) {
        return new CommandContext(id, (UUID) request.getAttribute(RequestCorrelationFilter.ATTRIBUTE), action, reason);
    }

    public record CreationView(UUID commandId, String ownerOffice, String identifier, String kind,
                               DatasetMetadataForm metadata, List<DatasetOffice> offices,
                               Map<String, String> errors, String message) {}

    @GetMapping(DATASETS + "/{id}")
    String detail(Authentication authentication, @PathVariable UUID id, Model model,
                  @RequestParam(defaultValue = "") String search,
                  @RequestParam(defaultValue = "") String publicationStatus,
                  @RequestParam(defaultValue = "") String accessLevel,
                  @RequestParam(required = false) String ownerOffice,
                  @RequestParam(defaultValue = "0") int page,
                  @RequestParam(defaultValue = "false") boolean created,
                  @RequestParam(defaultValue = "") String result) {
        var actor = new VerifiedActorResolver().resolve(authentication);
        var dataset = query.detail(actor, id);
        var capabilities = authority.describe(actor, id);
        var actions = Arrays.stream(DatasetAction.values()).filter(action -> action.allowed(dataset, capabilities)).toList();
        String notice = created ? "Der Datensatz wurde als Entwurf erstellt." : switch (result) {
            case "saved" -> "Die Metadaten wurden gespeichert.";
            case "submit" -> "Der Datensatz wurde zur Prüfung eingereicht.";
            case "publish" -> "Der Datensatz wurde publiziert.";
            case "archive" -> "Der Datensatz wurde archiviert.";
            case "access" -> "Der Datenzugang wurde geändert.";
            default -> null;
        };
        var filters = new DatasetFilters(search, publicationStatus, accessLevel, ownerOffice, page);
        model.addAttribute("detail", new DetailView(dataset, canEdit(dataset, capabilities), actions,
            capabilities.access(), capabilities.metadata(), datasetUri(DATASETS, filters, filters.page()), notice,
            DatasetMetadataForm.emailAddress(dataset.contactEmail())));
        return "dataset/detail";
    }

    @GetMapping(DATASETS + "/{id}/edit")
    ModelAndView editForm(Authentication authentication, HttpServletRequest request, @PathVariable UUID id) {
        var actor = new VerifiedActorResolver().resolve(authentication);
        var dataset = query.detail(actor, id);
        requireEdit(dataset, authority.describe(actor, id));
        var metadata = new DatasetMetadataForm(dataset.title(), dataset.description(), dataset.contactName(),
            DatasetMetadataForm.emailAddress(dataset.contactEmail()), dataset.themes(), String.join("\n", dataset.keywords()));
        return editView(request, dataset, metadata, UUID.randomUUID(),
            dataset.publicationStatus().equals("published"), Map.of(), null, false, 200);
    }

    @PostMapping(DATASETS + "/{id}/edit")
    ModelAndView edit(Authentication authentication, HttpServletRequest request, @PathVariable UUID id,
                      @RequestParam UUID commandId,
                      @RequestParam boolean published, @ModelAttribute DatasetMetadataForm metadata) {
        var actor = new VerifiedActorResolver().resolve(authentication);
        var errors = metadata.errors();
        if (!errors.isEmpty()) {
            var dataset = query.detail(actor, id);
            requireEdit(dataset, authority.describe(actor, id));
            boolean stale = false;
            return editView(request, dataset, metadata, commandId, published, errors,
                stale ? "Der Datensatz wurde inzwischen geändert. Laden Sie den aktuellen Stand." : null,
                stale, stale ? 409 : 400);
        }
        try {
            catalog.update(actor, command(request, commandId, published ? "save_published" : "save_draft", null),
                id, metadata.metadata(), published);
            return redirectToDataset(id, "result=saved");

        } catch (BusinessException error) {
            if (error.status() != 400 && error.status() != 409) {
                throw error;
            }
            var dataset = query.detail(actor, id);
            requireEdit(dataset, authority.describe(actor, id));
            return editView(request, dataset, metadata, commandId, published, Map.of(),
                error.getMessage(), false, error.status());
        }
    }

    private static boolean canEdit(DatasetDetail dataset, Capabilities capabilities) {
        return capabilities.metadata() && switch (dataset.publicationStatus()) {
            case "draft", "in_review" -> true;
            case "published" -> capabilities.publish();
            default -> false;
        };
    }

    private static void requireEdit(DatasetDetail dataset, Capabilities capabilities) {
        if (!canEdit(dataset, capabilities)) {
            throw BusinessException.forbidden();
        }
    }

    private static ModelAndView editView(HttpServletRequest request, DatasetDetail dataset, DatasetMetadataForm metadata,
                                         UUID commandId, boolean published, Map<String, String> errors,
                                         String message, boolean stale, int status) {
        var view = new ModelAndView("dataset/edit", HttpStatusCode.valueOf(status));
        view.addObject("edit", new EditView(dataset, metadata, commandId, published,
            Collections.unmodifiableMap(new LinkedHashMap<>(errors)), message, stale));
        view.addObject("csrf", request.getAttribute("_csrf"));
        return view;
    }

    static ModelAndView redirectToDataset(UUID id, String feedback) {
        var redirect = new RedirectView(DATASETS + "/" + id + "?" + feedback, true);
        redirect.setStatusCode(HttpStatus.SEE_OTHER);
        redirect.setExposeModelAttributes(false);
        return new ModelAndView(redirect);
    }

    public record DetailView(DatasetDetail dataset, boolean canEdit, List<DatasetAction> actions, boolean canManageGrants,
                             boolean canConfigureDelivery, String listHref, String notice, String contactAddress) {}

    public record EditView(DatasetDetail dataset, DatasetMetadataForm metadata, UUID commandId,
                           boolean published, Map<String, String> errors, String message, boolean stale) {}

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
