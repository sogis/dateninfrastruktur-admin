package ch.so.agi.dateninfrastruktur.admin.access;
import java.time.LocalDate;
import java.util.*;
import org.apache.cayenne.ObjectContext;
import org.apache.cayenne.runtime.CayenneRuntime;
import ch.so.agi.dateninfrastruktur.admin.identity.ActorContext;
import ch.so.agi.dateninfrastruktur.admin.persistence.CayenneSql;

/** Bounded read models. Every invocation creates a fresh context and checks current authority. */
public final class ManagementQueryService {
    private static final String DATASET_AUTHORITY = """
        EXISTS(SELECT 1 FROM di_management.authorityassignment aa JOIN eligible e ON e.t_id=aa.principal CROSS JOIN di_now n
          WHERE (aa.dataset=d.t_id OR aa.office=d.owneroffice) AND aa.revokedat IS NULL
            AND aa.validfrom<=n.observed_at AND (aa.validuntil IS NULL OR n.observed_at<aa.validuntil)
            AND %s)
        """;
    private static final String DATASET_MANAGEMENT_SCOPE =
        DATASET_AUTHORITY.formatted("aa.capability IN ('manage_metadata', 'publish', 'manage_access')");
    private static final String REQUEST_DECISION_SCOPE =
        DATASET_AUTHORITY.formatted("aa.capability = 'manage_access'");
    private final CayenneRuntime runtime;private final AuthorityService authority;
    public ManagementQueryService(CayenneRuntime runtime,AuthorityService authority){this.runtime=runtime;this.authority=authority;}
    private Map<String,Object> params(ActorContext actor){return new HashMap<>(Map.of("issuer",actor.issuer(),"subject",actor.subject()));}
    private List<Map<String,Object>> rows(ObjectContext ctx,String sql,Map<String,?> p){
        return CayenneSql.rows(ctx,sql,p).stream().map(r->Collections.unmodifiableMap(new HashMap<String,Object>(r))).toList();
    }
    private int offset(int page){if(page<0 || page>10000)throw BusinessException.invalid("Ungültige Seite.");return page*50;}
    private static final int DATASET_PAGE_SIZE = 50;
    private static final int MAX_PAGE = 10_000;

    public record DatasetFilters(String search, String publicationStatus, String accessLevel, String ownerOffice, int page) {
        public DatasetFilters {
            search = normalize(search);
            publicationStatus = normalize(publicationStatus);
            accessLevel = normalize(accessLevel);
            if (search.length() > 100) {
                throw BusinessException.invalid("Die Suche darf höchstens 100 Zeichen enthalten.");
            }
            if (!publicationStatus.isEmpty() && !Set.of("draft", "in_review", "published", "archived").contains(publicationStatus)) {
                throw BusinessException.invalid("Dieser Publikationsstatus ist ungültig.");
            }
            if (!accessLevel.isEmpty() && !Set.of("open", "restricted").contains(accessLevel)) {
                throw BusinessException.invalid("Dieser Datenzugang ist ungültig.");
            }
            if (page < 0 || page > MAX_PAGE) {
                throw BusinessException.invalid("Ungültige Seite.");
            }
        }

        private static String normalize(String value) {
            return value == null ? "" : value.strip();
        }
    }

    public record DatasetOffice(String id, String name, String abbreviation, String identifier) {}
    public record DatasetRow(UUID id, String identifier, String title, DatasetOffice office, String kind,
                             String publicationStatus, String accessLevel, LocalDate modified) {}
    public record DatasetPage(List<DatasetRow> rows, boolean hasNext, boolean hasPrevious, boolean hasDatasets,
                              List<DatasetOffice> offices, boolean canCreate) {}

    public DatasetPage datasets(ActorContext actor, DatasetFilters filters) {
        return runtime.performInTransaction(() -> {
            var context = runtime.newContext();
            var identity = params(actor);
            var parameters = new HashMap<>(identity);
            parameters.put("offset", filters.page() * DATASET_PAGE_SIZE);
            parameters.put("limit", DATASET_PAGE_SIZE + 1);
            var sql = new StringBuilder(AuthorityService.ELIGIBLE + """
                SELECT d.t_ili_tid AS id, d.identifier, d.title, d.akind AS kind, d.publicationstatus,
                       d.accesslevel, to_char(d.modified, 'YYYY-MM-DD') AS modified,
                       o.t_ili_tid AS office_id, o.aname AS office_name, o.abbreviation AS office_abbreviation,
                       o.identifier AS office_identifier
                FROM di_management.dataset d JOIN di_management.office o ON o.t_id = d.owneroffice
                WHERE
                """ + DATASET_MANAGEMENT_SCOPE);
            if (!filters.search().isEmpty()) {
                sql.append("""
                     AND (position(lower(#bind($search)) in lower(d.title)) > 0
                          OR position(lower(#bind($search)) in lower(d.identifier)) > 0)
                    """);
                parameters.put("search", filters.search());
            }
            if (!filters.publicationStatus().isEmpty()) {
                sql.append(" AND d.publicationstatus = #bind($publicationStatus)");
                parameters.put("publicationStatus", filters.publicationStatus());
            }
            if (!filters.accessLevel().isEmpty()) {
                sql.append(" AND d.accesslevel = #bind($accessLevel)");
                parameters.put("accessLevel", filters.accessLevel());
            }
            if (filters.ownerOffice() != null) {
                sql.append(" AND o.t_ili_tid = #bind($ownerOffice)");
                parameters.put("ownerOffice", filters.ownerOffice().toString());
            }
            sql.append(" ORDER BY d.title, d.t_ili_tid LIMIT #bind($limit) OFFSET #bind($offset)");

            var results = rows(context, sql.toString(), parameters);
            var datasets = results.stream().limit(DATASET_PAGE_SIZE).map(ManagementQueryService::datasetRow).toList();
            var offices = scopedDatasetOffices(context, identity);
            return new DatasetPage(datasets, filters.page() < MAX_PAGE && results.size() > DATASET_PAGE_SIZE,
                filters.page() > 0, !offices.isEmpty(), offices, !eligibleOwnerOffices(context, identity).isEmpty());
        });
    }

    private List<DatasetOffice> scopedDatasetOffices(ObjectContext context, Map<String, ?> identity) {
        return rows(context, AuthorityService.ELIGIBLE + """
            SELECT o.t_ili_tid AS office_id, o.aname AS office_name, o.abbreviation AS office_abbreviation,
                   o.identifier AS office_identifier
            FROM di_management.office o
            WHERE EXISTS (SELECT 1 FROM di_management.dataset d WHERE d.owneroffice = o.t_id AND
            """ + DATASET_MANAGEMENT_SCOPE + ") ORDER BY o.aname, o.abbreviation, o.identifier", identity)
            .stream().map(ManagementQueryService::datasetOffice).toList();
    }

    private static DatasetRow datasetRow(Map<String, Object> row) {
        return new DatasetRow(uuid(row, "id"), (String) row.get("identifier"), (String) row.get("title"),
            datasetOffice(row), (String) row.get("kind"), (String) row.get("publicationstatus"),
            (String) row.get("accesslevel"), LocalDate.parse((String) row.get("modified")));
    }

    private static DatasetOffice datasetOffice(Map<String, Object> row) {
        return new DatasetOffice((String) row.get("office_id"), (String) row.get("office_name"),
            (String) row.get("office_abbreviation"), (String) row.get("office_identifier"));
    }

    private static UUID uuid(Map<String, Object> row, String key) {
        return UUID.fromString(row.get(key).toString());
    }

    public record DatasetDetail(UUID id, String identifier, String title, String description, String contactName,
                                String contactEmail, List<String> themes, List<String> keywords,
                                DatasetOffice ownerOffice, DatasetOffice publisherOffice, String kind,
                                String publicationStatus, String accessLevel, LocalDate modified, boolean hasIssues) {}

    public DatasetDetail detail(ActorContext actor, UUID id) {
        return runtime.performInTransaction(() -> {
            var context = runtime.newContext();
            authority.requireAny(actor, id);
            var parameters = Map.of("id", id.toString());
            var result = rows(context, """
                SELECT d.t_ili_tid AS id, d.identifier, d.title, d.adescription AS description,
                       contact.aname AS contactname, contact.email AS contactemail, d.akind AS kind, d.publicationstatus, d.accesslevel,
                       to_char(d.modified, 'YYYY-MM-DD') AS modified,
                       EXISTS (SELECT 1 FROM di_management.datasetissue issue WHERE issue.dataset = d.t_id) AS has_issues,
                       owner.t_ili_tid AS office_id, owner.aname AS office_name,
                       owner.abbreviation AS office_abbreviation, owner.identifier AS office_identifier,
                       publisher.t_ili_tid AS publisher_id, publisher.aname AS publisher_name,
                       publisher.abbreviation AS publisher_abbreviation, publisher.identifier AS publisher_identifier
                FROM di_management.dataset d
                JOIN di_management.office owner ON owner.t_id = d.owneroffice
                JOIN di_management.office publisher ON publisher.t_id = d.publisheroffice
                LEFT JOIN LATERAL (
                    SELECT c.aname,c.email FROM di_management.datasetcontact link
                    JOIN di_management.contactpoint c ON c.t_id=link.contactpoints
                    WHERE link.datasets=d.t_id ORDER BY c.t_ili_tid LIMIT 1
                ) contact ON true
                WHERE d.t_ili_tid = CAST(#bind($id) AS uuid)
                """, parameters);
            if (result.isEmpty()) {
                throw BusinessException.missing();
            }
            var row = result.getFirst();
            var themes = rows(context, """
                SELECT t.acode AS theme FROM di_management.themeentry t
                JOIN di_management.dataset d ON d.t_id = t.dataset_themes
                WHERE d.t_ili_tid = CAST(#bind($id) AS uuid) ORDER BY theme
                """, parameters).stream().map(theme -> (String) theme.get("theme")).toList();
            var keywords = rows(context, """
                SELECT k.avalue AS keyword FROM di_management.keywordentry k
                JOIN di_management.dataset d ON d.t_id = k.dataset_keywords
                WHERE d.t_ili_tid = CAST(#bind($id) AS uuid) ORDER BY keyword
                """, parameters).stream().map(keyword -> (String) keyword.get("keyword")).toList();
            var publisher = new DatasetOffice((String) row.get("publisher_id"), (String) row.get("publisher_name"),
                (String) row.get("publisher_abbreviation"), (String) row.get("publisher_identifier"));
            return new DatasetDetail(id, (String) row.get("identifier"), (String) row.get("title"),
                (String) row.get("description"), (String) row.get("contactname"), (String) row.get("contactemail"),
                themes, keywords, datasetOffice(row), publisher, (String) row.get("kind"),
                (String) row.get("publicationstatus"), (String) row.get("accesslevel"),
                LocalDate.parse((String) row.get("modified")),
                Boolean.TRUE.equals(row.get("has_issues")));
        });
    }

    public Map<String,Object> requestDataset(UUID id){return runtime.performInTransaction(()->{
        var result=rows(runtime.newContext(),"SELECT t_ili_tid AS id,title,accesslevel AS access FROM di_management.dataset WHERE t_ili_tid=CAST(#bind($id) AS uuid) AND publicationstatus='published'",Map.of("id",id.toString()));
        if(result.isEmpty())throw BusinessException.missing();return result.getFirst();
    });}
    public List<Map<String,Object>> requests(ActorContext actor,boolean own,int page){return runtime.performInTransaction(()->{
        var p=params(actor);p.put("offset",offset(page));
        String filter=own?"p.aissuer=#bind($issuer) AND p.asubject=#bind($subject)":REQUEST_DECISION_SCOPE+" AND r.astatus='pending'";
        return rows(runtime.newContext(),AuthorityService.ELIGIBLE+"""
          SELECT r.t_ili_tid AS id,d.t_ili_tid AS dataset,d.title,r.astatus AS status,r.reason,r.requestedat,r.requestedvaliduntil,
                 p.t_ili_tid AS applicant,p.displayname,p.asubject AS subject
          FROM di_management.accessrequest r JOIN di_management.dataset d ON d.t_id=r.dataset
          JOIN di_management.principal p ON p.t_id=r.applicant WHERE
          """+filter+" ORDER BY r.requestedat DESC,r.t_id DESC LIMIT 50 OFFSET #bind($offset)",p);
    });}
    public List<DatasetOffice> eligibleOwnerOffices(ActorContext actor) {
        return runtime.performInTransaction(() -> eligibleOwnerOffices(runtime.newContext(), params(actor)));
    }

    private List<DatasetOffice> eligibleOwnerOffices(ObjectContext context, Map<String, ?> identity) {
        return rows(context, AuthorityService.ELIGIBLE + """
            SELECT DISTINCT o.t_ili_tid AS office_id, o.aname AS office_name, o.abbreviation AS office_abbreviation,
                   o.identifier AS office_identifier
            FROM di_management.office o
            JOIN di_management.authorityassignment aa ON aa.office = o.t_id
            JOIN eligible e ON e.t_id = aa.principal CROSS JOIN di_now n
            WHERE o.aactive AND aa.capability = 'manage_metadata' AND aa.revokedat IS NULL
              AND aa.validfrom <= n.observed_at AND (aa.validuntil IS NULL OR n.observed_at < aa.validuntil)
            ORDER BY office_name, office_abbreviation, office_identifier
            """, identity).stream().map(ManagementQueryService::datasetOffice).toList();
    }
    public List<Map<String,Object>> principals(ActorContext actor,UUID dataset,String term){
        if(term==null || term.length()<2 || term.length()>100)throw BusinessException.invalid("Bitte 2 bis 100 Suchzeichen eingeben.");
        return runtime.performInTransaction(()->{var ctx=runtime.newContext();authority.require(ctx,actor,dataset,"manage_access");
            return rows(ctx,"SELECT t_ili_tid AS id,akind AS kind,displayname,aissuer AS issuer,asubject AS subject FROM di_management.principal WHERE aactive AND (position(lower(#bind($term)) in lower(coalesce(displayname,'')||' '||coalesce(asubject,'')))>0) ORDER BY displayname NULLS LAST,t_id LIMIT 20",Map.of("term",term));});
    }
    public record DatasetIssue(UUID id, String key, String label, String note, String publicationStatus,
                               boolean current, LocalDate modified) {}
    public record DatasetVersion(String key, String availability, String validationStatus, LocalDate date, boolean current) {}
    public record IssueVersions(DatasetIssue issue, List<DatasetVersion> versions) {}

    private static final String ISSUES = """
        SELECT i.t_ili_tid AS id, i.issuekey, i.label, i.note, i.publicationstatus,
               i.t_id = d.currentissue AS current, to_char(i.modified, 'YYYY-MM-DD') AS modified
        FROM di_management.datasetissue i JOIN di_management.dataset d ON d.t_id = i.dataset
        WHERE d.t_ili_tid = CAST(#bind($dataset) AS uuid)
        """;

    public List<DatasetIssue> issues(ActorContext actor, UUID dataset) {
        return runtime.performInTransaction(() -> {
            authority.requireAny(actor, dataset);
            return rows(runtime.newContext(), ISSUES + " ORDER BY i.issuekey, i.t_ili_tid",
                Map.of("dataset", dataset.toString())).stream().map(ManagementQueryService::datasetIssue).toList();
        });
    }

    public DatasetIssue issue(ActorContext actor, UUID dataset, UUID issue) {
        return runtime.performInTransaction(() -> {
            authority.requireAny(actor, dataset);
            return requireIssue(runtime.newContext(), issueParams(dataset, issue));
        });
    }

    public IssueVersions versions(ActorContext actor, UUID dataset, UUID issue) {
        return runtime.performInTransaction(() -> {
            authority.requireAny(actor, dataset);
            var context = runtime.newContext();
            var parameters = issueParams(dataset, issue);
            var selected = requireIssue(context, parameters);
            var versions = rows(context, """
                SELECT v.versionkey, v.availability, v.validationstatus, v.t_id = i.currentversion AS current,
                       to_char(COALESCE(v.acceptedat, v.validationat), 'YYYY-MM-DD') AS observed_date
                FROM di_management.dataversion v
                JOIN di_management.datasetissue i ON i.t_id = v.issue
                JOIN di_management.dataset d ON d.t_id = i.dataset
                WHERE d.t_ili_tid = CAST(#bind($dataset) AS uuid)
                  AND i.t_ili_tid = CAST(#bind($issue) AS uuid)
                ORDER BY v.asequence DESC, v.t_ili_tid
                """, parameters).stream().map(row -> {
                    String date = (String) row.get("observed_date");
                    return new DatasetVersion((String) row.get("versionkey"), (String) row.get("availability"),
                        (String) row.get("validationstatus"), date == null ? null : LocalDate.parse(date),
                        Boolean.TRUE.equals(row.get("current")));
                }).toList();
            return new IssueVersions(selected, versions);
        });
    }

    private DatasetIssue requireIssue(ObjectContext context, Map<String, String> parameters) {
        var result = rows(context, ISSUES + " AND i.t_ili_tid = CAST(#bind($issue) AS uuid)", parameters);
        if (result.isEmpty()) {
            throw BusinessException.missing();
        }
        return datasetIssue(result.getFirst());
    }

    private static Map<String, String> issueParams(UUID dataset, UUID issue) {
        return Map.of("dataset", dataset.toString(), "issue", issue.toString());
    }

    private static DatasetIssue datasetIssue(Map<String, Object> row) {
        return new DatasetIssue(uuid(row, "id"), (String) row.get("issuekey"), (String) row.get("label"),
            (String) row.get("note"), (String) row.get("publicationstatus"), Boolean.TRUE.equals(row.get("current")),
            LocalDate.parse((String) row.get("modified")));
    }

    public List<Map<String,Object>> grants(ActorContext actor,UUID dataset){return runtime.performInTransaction(()->{
        var ctx=runtime.newContext();authority.require(ctx,actor,dataset,"manage_access");
        return rows(ctx,"""
          SELECT g.t_ili_tid AS id,p.t_ili_tid AS beneficiary,p.akind AS kind,p.displayname,p.asubject AS subject,
            g.validfrom,g.validuntil,g.revokedat,CASE WHEN g.revokedat IS NOT NULL THEN 'entzogen'
            WHEN g.validfrom>timezone('UTC',clock_timestamp()) THEN 'zukünftig'
            WHEN g.validuntil<=timezone('UTC',clock_timestamp()) THEN 'abgelaufen' ELSE 'wirksam' END AS status
          FROM di_management.accessgrant g JOIN di_management.dataset d ON d.t_id=g.dataset
          JOIN di_management.principal p ON p.t_id=g.beneficiary WHERE d.t_ili_tid=CAST(#bind($id) AS uuid)
          ORDER BY g.t_id DESC LIMIT 100
          """,Map.of("id",dataset.toString()));
    });}
    public List<Map<String,Object>> effective(ActorContext actor){return runtime.performInTransaction(()->rows(runtime.newContext(),AuthorityService.ELIGIBLE+"""
        SELECT d.t_ili_tid AS dataset,d.title,p.akind AS via_kind,p.displayname,p.t_ili_tid AS via_principal,g.t_ili_tid AS grant_id,g.validuntil
        FROM di_management.accessgrant g JOIN eligible e ON e.t_id=g.beneficiary
        JOIN di_management.principal p ON p.t_id=e.t_id JOIN di_management.dataset d ON d.t_id=g.dataset CROSS JOIN di_now n
        WHERE d.publicationstatus='published' AND g.revokedat IS NULL AND g.validfrom<=n.observed_at
          AND (g.validuntil IS NULL OR n.observed_at<g.validuntil) ORDER BY d.title,g.t_id LIMIT 100
        """,params(actor)));}
}
