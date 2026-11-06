package ch.so.agi.dateninfrastruktur.admin.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ch.so.agi.dateninfrastruktur.admin.access.ManagementQueryService;
import ch.so.agi.dateninfrastruktur.admin.access.ManagementQueryService.DatasetFilters;
import ch.so.agi.dateninfrastruktur.admin.access.ManagementQueryService.DatasetOffice;
import ch.so.agi.dateninfrastruktur.admin.access.ManagementQueryService.DatasetRow;
import ch.so.agi.dateninfrastruktur.admin.security.VerifiedActorResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;

@Controller
@ConditionalOnProperty(name = "di.web.enabled", havingValue = "true", matchIfMissing = true)
public final class DatasetController {
    private static final String DATASETS = "/management/datasets";

    private final ManagementQueryService query;

    public DatasetController(ManagementQueryService query) {
        this.query = query;
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
