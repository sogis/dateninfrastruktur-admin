package ch.so.agi.dateninfrastruktur.admin.delivery;

import ch.so.agi.dateninfrastruktur.admin.access.AuthorityService;
import ch.so.agi.dateninfrastruktur.admin.access.BusinessException;
import ch.so.agi.dateninfrastruktur.admin.identity.ActorContext;
import ch.so.agi.dateninfrastruktur.admin.identity.PrincipalRepository;
import ch.so.agi.dateninfrastruktur.admin.model.*;
import ch.so.agi.dateninfrastruktur.admin.persistence.CayenneSql;
import ch.so.agi.dateninfrastruktur.admin.persistence.ManagementBaskets;
import ch.so.agi.dateninfrastruktur.admin.persistence.ManagementTransactions;
import org.apache.cayenne.ObjectContext;
import org.apache.cayenne.query.ObjectSelect;
import org.apache.cayenne.runtime.CayenneRuntime;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

import static ch.so.agi.dateninfrastruktur.admin.persistence.ManagementBaskets.Topic.Catalog;
import static ch.so.agi.dateninfrastruktur.admin.persistence.ManagementBaskets.Topic.Delivery;

public final class DeliveryService {
    public record Create(UUID datasetId, UUID issueId, String mode, String originalFileName,
                         Long inputSize, String inputSha256) {}
    public record Complete(UUID schemaId, UUID baseVersionId, String fileName,
                           long byteSize, String sha256, long objectCount) {}
    public record Column(String name, String dataType, boolean mandatory, Integer decimalPlaces) {}
    public record State(UUID id, UUID datasetId, UUID issueId, UUID schemaId, UUID baseVersionId,
                        UUID acceptedVersionId, String mode, String status, String candidatePrefix, String schemaModel,
                        String schemaModelName, String baseCsvKey, Long inputSize, String inputSha256,
                        List<Column> columns) {}
    public record Eligible(UUID datasetId, String title, UUID issueId, String issueKey, String allowedModes) {}

    private final CayenneRuntime runtime;
    private final AuthorityService authority;
    private final PrincipalRepository principals = new PrincipalRepository();
    private final CandidateFiles files;

    public DeliveryService(CayenneRuntime runtime, AuthorityService authority, CandidateFiles files) {
        this.runtime = runtime;
        this.authority = authority;
        this.files = files;
    }

    private <T> T transaction(Function<ObjectContext, T> work) {
        return ManagementTransactions.write(runtime, work);
    }

    public List<Eligible> eligible(ActorContext actor, String search, int offset, int limit) {
        if (search == null || search.length() > 255 || offset < 0 || limit < 1 || limit > 100) {
            throw BusinessException.invalid("Ungültige Suchparameter.");
        }
        return transaction(context -> {
            requirePrincipal(context, actor, "user");
            return CayenneSql.rows(context, AuthorityService.ELIGIBLE + """
                SELECT d.t_ili_tid AS dataset, d.title, i.t_ili_tid AS issue, i.issuekey, c.allowedmodes
                FROM di_management.datasetissue i
                JOIN di_management.dataset d ON d.t_id=i.dataset
                JOIN di_management.deliveryconfiguration c ON c.dataset=d.t_id
                JOIN di_management.dataschema schema ON schema.t_id=i.aschema AND schema.akind='simple_csv'
                WHERE d.publicationstatus='published' AND i.publicationstatus='published'
                  AND i.aschema IS NOT NULL AND c.enabled AND c.aformat='csv'
                  AND strpos(lower(d.title || ' ' || d.identifier || ' ' || i.issuekey), lower(#bind($search)))>0
                  AND EXISTS (
                    SELECT 1 FROM di_management.authorityassignment a JOIN eligible e ON e.t_id=a.principal
                    CROSS JOIN di_now n
                    WHERE a.capability='submit_data' AND (a.dataset=d.t_id OR (a.office=d.owneroffice
                        AND EXISTS (SELECT 1 FROM di_management.office o WHERE o.t_id=a.office AND o.aactive)))
                      AND a.revokedat IS NULL AND a.validfrom<=n.observed_at
                      AND (a.validuntil IS NULL OR n.observed_at<a.validuntil))
                ORDER BY d.identifier, i.issuekey OFFSET #bind($offset) LIMIT #bind($limit)
                """, Map.of("issuer", actor.issuer(), "subject", actor.subject(),
                    "search", search.isEmpty() ? "" : search, "offset", offset, "limit", limit))
                .stream().map(row -> new Eligible(UUID.fromString(row.get("dataset").toString()),
                    row.get("title").toString(), UUID.fromString(row.get("issue").toString()),
                    row.get("issuekey").toString(), row.get("allowedmodes").toString())).toList();
        });
    }

    public State create(ActorContext actor, UUID id, Create input) {
        if (input == null || input.datasetId() == null || input.issueId() == null
            || !("append".equals(input.mode()) || "replace".equals(input.mode()))
            || (input.originalFileName() != null && (input.originalFileName().isBlank() || input.originalFileName().length() > 255))
            || (input.inputSize() != null && (input.inputSize() < 0 || input.inputSize() > 999999999999999999L))
            || (input.inputSha256() != null && !CandidateFiles.isHash(input.inputSha256()))) {
            throw BusinessException.invalid("Ungültige Lieferdaten.");
        }
        return transaction(context -> {
            var person = requirePrincipal(context, actor, "user");
            // Serialize creation of the same caller-supplied identity even when no row exists yet.
            CayenneSql.rows(context, "SELECT pg_advisory_xact_lock(hashtextextended(#bind($id), 173))",
                Map.of("id", id.toString()));
            var existing = find(context, id);
            if (existing != null) {
                if (!samePrincipal(existing.getSubmittedby1(), person)) throw BusinessException.forbidden();
                if (!input.equals(createFields(existing))) throw BusinessException.conflict("Liefer-ID bereits vergeben.");
                return state(existing);
            }
            var issue = ObjectSelect.query(Datasetissue.class).where(Datasetissue.T_ILI_TID.eq(input.issueId())).selectOne(context);
            if (issue == null || !input.datasetId().equals(issue.getDataset1().getTIliTid())) throw BusinessException.missing();
            requireSubmit(context, person, issue, input.mode());
            var row = context.newObject(Submission.class);
            row.setTIliTid(id);
            row.setTBasket(ManagementBaskets.require(context, Delivery));
            row.setDataset1(issue.getDataset1());
            row.setIssue1(issue);
            row.setSubmittedby1(person);
            row.setCreatedat(CayenneSql.now(context));
            row.setAstatus("pending");
            row.setAmode(input.mode());
            row.setOriginalfilename(input.originalFileName());
            row.setInputsize(input.inputSize());
            row.setInputsha256(input.inputSha256());
            return state(row);
        });
    }

    public State get(ActorContext actor, UUID id) {
        return transaction(context -> {
            var person = requirePrincipal(context, actor, "user");
            var row = required(context, id);
            if (!samePrincipal(row.getSubmittedby1(), person)) {
                authority.require(context, actor, row.getDataset1().getTIliTid(), "manage_submitters");
            }
            return state(row);
        });
    }

    public State cancel(ActorContext actor, UUID id) {
        return transaction(context -> {
            var person = requirePrincipal(context, actor, "user");
            var row = locked(context, id);
            if (!samePrincipal(row.getSubmittedby1(), person)) throw BusinessException.forbidden();
            expect(row, "pending");
            row.setAstatus("cancelled");
            row.setCompletedat(CayenneSql.now(context));
            return state(row);
        });
    }

    public State start(ActorContext service, UUID id) {
        return transaction(context -> {
            requirePrincipal(context, service, "service");
            var row = locked(context, id);
            expect(row, "pending");
            lockSubmissionTarget(context, id);
            var issue = row.getIssue1();
            if (!row.getDataset1().getTIliTid().equals(issue.getDataset1().getTIliTid())) throw BusinessException.conflict("Ausgabe gehört nicht zur Lieferung.");
            requireSubmit(context, row.getSubmittedby1(), issue, row.getAmode());
            if ("append".equals(row.getAmode()) && (issue.getCurrentversion1() == null
                    || !sameVersionSchema(issue.getCurrentversion1(), issue.getAschema1()))) {
                throw BusinessException.conflict("Append benötigt eine Basis mit demselben Schema.");
            }
            row.setAschema1(issue.getAschema1());
            row.setBaseversion1(issue.getCurrentversion1());
            row.setStartedat(CayenneSql.now(context));
            row.setAstatus("processing");
            return state(row);
        });
    }

    public State complete(ActorContext service, UUID id, Complete input) {
        CandidateFiles.validate(input);
        var replay = transaction(context -> {
            requirePrincipal(context, service, "service");
            var row = required(context, id);
            if ("accepted".equals(row.getAstatus())) return replay(row, input);
            expect(row, "processing");
            return null;
        });
        if (replay != null) return replay;

        String objectKey = files.verify(distributionId(id), input);
        return transaction(context -> {
            var executor = requirePrincipal(context, service, "service");
            var row = locked(context, id);
            if ("accepted".equals(row.getAstatus())) return replay(row, input);
            expect(row, "processing");
            lockSubmissionTarget(context, id);
            var issue = row.getIssue1();
            if (!row.getDataset1().getTIliTid().equals(issue.getDataset1().getTIliTid())) throw BusinessException.conflict("Ausgabe gehört nicht zur Lieferung.");
            requireSubmit(context, row.getSubmittedby1(), issue, row.getAmode());
            if (!Objects.equals(id(row.getBaseversion1()), id(issue.getCurrentversion1()))
                || !Objects.equals(input.baseVersionId(), id(row.getBaseversion1()))
                || !input.schemaId().equals(id(row.getAschema1()))
                || !input.schemaId().equals(id(issue.getAschema1()))) {
                throw BusinessException.conflict("Ausgangsversion oder Schema hat sich geändert.");
            }
            if (row.getOriginalfilename() == null || row.getInputsha256() == null || row.getInputsize() == null) {
                throw BusinessException.conflict("Die geprüfte Eingabedatei ist nicht dokumentiert.");
            }
            var now = CayenneSql.now(context);
            long basket = ManagementBaskets.require(context, Catalog);
            var version = context.newObject(Dataversion.class);
            version.setTIliTid(UUID.randomUUID());
            version.setTBasket(basket);
            version.setIssue1(issue);
            version.setAschema1(row.getAschema1());
            version.setVersionkey(id.toString());
            int sequence = issue.getDataversions().stream().mapToInt(Dataversion::getAsequence).max().orElse(0);
            version.setAsequence(sequence + 1);
            version.setAcceptedat(now);
            version.setAvailability("available");
            version.setValidationstatus("success");
            version.setObjectcount(input.objectCount());
            version.setSourcesha256(row.getInputsha256());
            context.commitChanges();
            var distribution = context.newObject(Distribution.class);
            distribution.setTIliTid(distributionId(id));
            distribution.setTBasket(basket);
            distribution.setVersion1(version);
            distribution.setAformat("csv");
            distribution.setFilename(input.fileName());
            distribution.setMediatype("text/csv");
            distribution.setAvailability("available");
            distribution.setBytesize(input.byteSize());
            distribution.setSha256(input.sha256());
            distribution.setObjectkey(objectKey);
            context.commitChanges();
            var action = context.newObject(Catalogaction.class);
            action.setTIliTid(UUID.randomUUID());
            action.setTBasket(ManagementBaskets.require(context, ManagementBaskets.Topic.Access));
            action.setDataset1(row.getDataset1());
            action.setActor1(executor);
            action.setInitiator1(row.getSubmittedby1());
            action.setAffectedversion1(version);
            action.setAffecteddistribution1(distribution);
            action.setActioncode("accept_delivery");
            action.setReason("Geprüfte Lieferung angenommen");
            action.setPerformedat(now);
            action.setCommandid(UUID.randomUUID());
            issue.setCurrentversion1(version);
            row.setAcceptedversion1(version);
            row.setCompletedat(now);
            row.setAstatus("accepted");
            return state(row);
        });
    }

    public State finish(ActorContext service, UUID id, String status, String message) {
        if (!List.of("rejected", "failed").contains(status) || message == null
                || message.isBlank() || message.length() > 512) throw BusinessException.invalid("Ungültiger Abschluss.");
        return transaction(context -> {
            requirePrincipal(context, service, "service");
            var row = locked(context, id);
            expect(row, "processing");
            row.setCompletedat(CayenneSql.now(context));
            row.setResultmessage(message);
            row.setAstatus(status);
            return state(row);
        });
    }

    private Principal requirePrincipal(ObjectContext context, ActorContext actor, String kind) {
        var person = principals.requireActive(context, actor);
        if (!kind.equals(person.getAkind())) throw BusinessException.forbidden();
        return person;
    }

    private void requireSubmit(ObjectContext context, Principal person, Datasetissue issue, String mode) {
        var dataset = issue.getDataset1();
        if (!person.isAactive() || !"user".equals(person.getAkind())) throw BusinessException.forbidden();
        var parameters = Map.of("principal", person.getTIliTid().toString(), "dataset", dataset.getTIliTid().toString());
        CayenneSql.rows(context, """
            SELECT t_id FROM di_management.principal
            WHERE t_ili_tid=CAST(#bind($principal) AS uuid) FOR SHARE
            """, parameters);
        CayenneSql.rows(context, """
            SELECT m.t_id FROM di_management.groupmembership m
            JOIN di_management.principal p ON p.t_id=m.amember
            JOIN di_management.principal g ON g.t_id=m.agroup
            WHERE p.t_ili_tid=CAST(#bind($principal) AS uuid) FOR SHARE OF m,g
            """, parameters);
        CayenneSql.rows(context, """
            SELECT a.t_id FROM di_management.authorityassignment a
            JOIN di_management.dataset d ON a.dataset=d.t_id OR a.office=d.owneroffice
            WHERE d.t_ili_tid=CAST(#bind($dataset) AS uuid) AND a.capability='submit_data'
            ORDER BY a.t_id FOR SHARE OF a
            """, parameters);
        authority.require(context, new ActorContext(person.getAissuer(), person.getAsubject(), "user", person.getTIliTid()),
            dataset.getTIliTid(), "submit_data");
        if (!"published".equals(dataset.getPublicationstatus()) || !"published".equals(issue.getPublicationstatus())) {
            throw BusinessException.conflict("Datensatz oder Ausgabe ist nicht publiziert.");
        }
        var configurations = dataset.getDeliveryconfigurations();
        if (configurations.size() != 1 || issue.getAschema1() == null) throw BusinessException.conflict("Keine Lieferkonfiguration.");
        var config = configurations.getFirst();
        if (!config.isEnabled() || !"csv".equals(config.getAformat()) || !"simple_csv".equals(issue.getAschema1().getAkind())
                || !("replace_and_append".equals(config.getAllowedmodes()) || (mode + "_only").equals(config.getAllowedmodes()))) {
            throw BusinessException.conflict("Liefermodus ist nicht freigegeben.");
        }
    }

    private static void lock(ObjectContext context, String table, UUID id) {
        if (CayenneSql.rows(context, "SELECT t_id FROM di_management." + table
            + " WHERE t_ili_tid=CAST(#bind($id) AS uuid) FOR UPDATE", Map.of("id", id.toString())).isEmpty()) {
            throw BusinessException.missing();
        }
    }

    private static void lockSubmissionTarget(ObjectContext context, UUID submission) {
        var parameters = Map.of("id", submission.toString());
        var datasets = CayenneSql.rows(context, """
            SELECT d.t_id FROM di_management.dataset d
            JOIN di_management.submission s ON s.dataset=d.t_id
            WHERE s.t_ili_tid=CAST(#bind($id) AS uuid) FOR UPDATE OF d
            """, parameters);
        if (datasets.size() != 1) throw BusinessException.missing();
        var rows = CayenneSql.rows(context, """
            SELECT i.t_id FROM di_management.datasetissue i
            JOIN di_management.submission s ON s.issue=i.t_id
            WHERE s.t_ili_tid=CAST(#bind($id) AS uuid) FOR UPDATE OF i
            """, parameters);
        if (rows.size() != 1) throw BusinessException.missing();
    }

    private static Submission locked(ObjectContext context, UUID id) {
        lock(context, "submission", id);
        return required(context, id);
    }

    private static Submission find(ObjectContext context, UUID id) {
        return ObjectSelect.query(Submission.class).where(Submission.T_ILI_TID.eq(id)).selectOne(context);
    }

    private static Submission required(ObjectContext context, UUID id) {
        var row = find(context, id);
        if (row == null) throw BusinessException.missing();
        return row;
    }

    private static void expect(Submission row, String expected) {
        if (!expected.equals(row.getAstatus())) throw BusinessException.conflict("Lieferung hat bereits einen anderen Status.");
    }

    private static boolean samePrincipal(Principal first, Principal second) {
        return first.getTIliTid().equals(second.getTIliTid());
    }

    private static boolean sameVersionSchema(Dataversion version, Dataschema schema) {
        return Objects.equals(id(version.getAschema1()), id(schema));
    }

    private static UUID id(Dataversion version) { return version == null ? null : version.getTIliTid(); }
    private static UUID id(Dataschema schema) { return schema == null ? null : schema.getTIliTid(); }

    private static Create createFields(Submission row) {
        return new Create(row.getDataset1().getTIliTid(), row.getIssue1().getTIliTid(), row.getAmode(),
            row.getOriginalfilename(), row.getInputsize(), row.getInputsha256());
    }

    private static UUID distributionId(UUID submission) {
        return UUID.nameUUIDFromBytes(("distribution:" + submission).getBytes(StandardCharsets.UTF_8));
    }

    private static State state(Submission row) {
        boolean processing = "processing".equals(row.getAstatus());
        var schema = row.getAschema1();
        var columns = processing ? CsvSchemaModel.orderedAttributes(schema).stream()
            .map(attribute -> new Column(attribute.getAname(), attribute.getDatatype(),
                attribute.isMandatory(), attribute.getDecimalplaces())).toList() : null;
        return new State(row.getTIliTid(), row.getDataset1().getTIliTid(), row.getIssue1().getTIliTid(),
            id(schema), id(row.getBaseversion1()), id(row.getAcceptedversion1()), row.getAmode(), row.getAstatus(),
            processing ? "downloads/" + distributionId(row.getTIliTid()) + "/" : null,
            processing ? CsvSchemaModel.render(schema) : null,
            processing ? schema.getModelname() : null,
            processing && "append".equals(row.getAmode()) ? baseCsvKey(row.getBaseversion1()) : null,
            row.getInputsize(), row.getInputsha256(), columns);
    }

    private static String baseCsvKey(Dataversion version) {
        var keys = version.getDistributions().stream()
            .filter(distribution -> "csv".equals(distribution.getAformat())
                && "available".equals(distribution.getAvailability()))
            .map(distribution -> {
                String fileName = distribution.getFilename();
                if (!CandidateFiles.isCsvFileName(fileName)) return null;
                String key = "downloads/" + distribution.getTIliTid() + "/" + fileName;
                return key.equals(distribution.getObjectkey()) ? key : null;
            })
            .filter(Objects::nonNull).toList();
        if (keys.size() != 1) {
            throw BusinessException.conflict("Append benötigt genau eine verfügbare Basis-CSV.");
        }
        return keys.getFirst();
    }

    private static State replay(Submission row, Complete input) {
        var version = row.getAcceptedversion1();
        var distributions = version.getDistributions();
        if (distributions.size() == 1) {
            var distribution = distributions.getFirst();
            var recorded = new Complete(id(row.getAschema1()), id(row.getBaseversion1()), distribution.getFilename(),
                distribution.getBytesize(), distribution.getSha256(), version.getObjectcount());
            if (recorded.equals(input)) return state(row);
        }
        throw BusinessException.conflict("Abschluss unterscheidet sich vom gespeicherten Ergebnis.");
    }
}
