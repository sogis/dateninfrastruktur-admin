package ch.so.agi.dateninfrastruktur.admin.delivery;

import ch.so.agi.dateninfrastruktur.admin.access.*;
import ch.so.agi.dateninfrastruktur.admin.audit.ActorContext;
import ch.so.agi.dateninfrastruktur.admin.model.*;
import ch.so.agi.dateninfrastruktur.admin.persistence.*;
import org.apache.cayenne.query.ObjectSelect;
import org.apache.cayenne.runtime.CayenneRuntime;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class DeliveryManagement {
    private final CayenneRuntime runtime;
    private final AuthorityService authority;
    private final SubmitterAdministrationService submitters;

    public DeliveryManagement(CayenneRuntime runtime, AuthorityService authority, SubmitterAdministrationService submitters) {
        this.runtime = runtime;
        this.authority = authority;
        this.submitters = submitters;
    }

    public List<Map<String, Object>> datasets(ActorContext actor) {
        return ManagementTransactions.write(runtime, context -> CayenneSql.rows(context, AuthorityService.ELIGIBLE + """
            SELECT DISTINCT d.t_ili_tid AS id, d.title
            FROM di_management.dataset d JOIN di_management.authorityassignment a
                ON a.dataset=d.t_id OR a.office=d.owneroffice
            JOIN eligible e ON e.t_id=a.principal CROSS JOIN di_now n
            WHERE a.capability IN ('manage_metadata','manage_submitters')
              AND a.revokedat IS NULL AND a.validfrom<=n.observed_at
              AND (a.validuntil IS NULL OR n.observed_at<a.validuntil)
            ORDER BY d.title, d.t_ili_tid LIMIT 100
            """, Map.of("issuer", actor.issuer(), "subject", actor.subject())));
    }

    public List<Map<String, Object>> submissions(ActorContext actor, UUID dataset) {
        return ManagementTransactions.write(runtime, context -> {
            submitters.requireManage(context, actor, new SubmitterAdministrationService.Scope(null, dataset));
            return CayenneSql.rows(context, """
                SELECT s.t_ili_tid AS id, s.astatus AS status, s.amode AS mode, s.createdat, i.issuekey AS issue
                FROM di_management.submission s JOIN di_management.dataset d ON d.t_id=s.dataset
                JOIN di_management.datasetissue i ON i.t_id=s.issue
                WHERE d.t_ili_tid=CAST(#bind($id) AS uuid) ORDER BY s.createdat DESC LIMIT 100
                """, Map.of("id", dataset.toString()));
        });
    }

    public List<Map<String, Object>> assignments(ActorContext actor, UUID dataset) {
        return ManagementTransactions.write(runtime, context -> {
            submitters.requireManage(context, actor, new SubmitterAdministrationService.Scope(null, dataset));
            return CayenneSql.rows(context, """
                SELECT a.t_ili_tid AS id, p.displayname, p.asubject AS subject, a.validfrom, a.validuntil, a.revokedat
                FROM di_management.authorityassignment a JOIN di_management.principal p ON p.t_id=a.principal
                JOIN di_management.dataset d ON d.t_id=a.dataset
                WHERE a.capability='submit_data' AND d.t_ili_tid=CAST(#bind($id) AS uuid)
                ORDER BY a.t_id DESC LIMIT 100
                """, Map.of("id", dataset.toString()));
        });
    }

    public UUID createSchema(ActorContext actor, UUID datasetId, List<String> columns,
                             List<String> unique, Integer minimum) {
        if (columns == null || columns.isEmpty() || columns.size() > 50 || unique == null
                || (minimum != null && (minimum < 1 || minimum > 999999999))) {
            throw BusinessException.invalid("Ungültige CSV-Schemadefinition.");
        }
        var names = columns.stream().map(CsvSchemaModel::identifier).toList();
        var key = unique.stream().map(CsvSchemaModel::identifier).toList();
        if (Set.copyOf(names).size() != names.size() || Set.copyOf(key).size() != key.size() || !names.containsAll(key)) {
            throw BusinessException.invalid("Spalten und Schlüssel müssen eindeutig sein.");
        }
        return ManagementTransactions.write(runtime, context -> {
            ManagementTransactions.lockDataset(context, datasetId);
            authority.require(context, actor, datasetId, "manage_metadata");
            long basket = ManagementBaskets.require(context, ManagementBaskets.Topic.Catalog);
            var schema = context.newObject(Dataschema.class);
            UUID id = UUID.randomUUID();
            schema.setTIliTid(id);
            schema.setTBasket(basket);
            schema.setIdentifier("csv-" + id);
            schema.setAkind("simple_csv");
            schema.setModelname("DI_Csv_" + id.toString().replace("-", ""));
            schema.setMinimumobjectcount(minimum);
            context.commitChanges();
            var attributes = new java.util.LinkedHashMap<String, Attributedefinition>();
            for (String name : names) {
                var attribute = context.newObject(Attributedefinition.class);
                attribute.setTIliTid(UUID.randomUUID());
                attribute.setTBasket(basket);
                attribute.setAname(name);
                attribute.setDatatype("text");
                attribute.setMaxlength(255);
                attribute.setMandatory(true);
                attribute.setOrdinal(attributes.size() + 1);
                attribute.setAschema1(schema);
                attributes.put(name, attribute);
            }
            if (!key.isEmpty()) {
                var rule = context.newObject(Uniquedefinition.class);
                rule.setTIliTid(UUID.randomUUID());
                rule.setTBasket(basket);
                rule.setAname("Key");
                rule.setAschema1(schema);
                context.commitChanges();
                int position = 1;
                for (String name : key) {
                    var field = context.newObject(Uniquefield.class);
                    field.setTIliTid(UUID.randomUUID());
                    field.setTBasket(basket);
                    field.setOrdinal(position++);
                    field.setAattribute1(attributes.get(name));
                    field.setUniquedefinition1(rule);
                }
            }
            CsvSchemaModel.render(schema);
            return id;
        });
    }

    public void configure(ActorContext actor, UUID datasetId, UUID issueId, UUID schemaId, boolean enabled, String modes) {
        if (!Set.of("replace_only","append_only","replace_and_append").contains(modes)) throw BusinessException.invalid("Ungültiger Liefermodus.");
        ManagementTransactions.write(runtime, context -> {
            ManagementTransactions.lockDataset(context, datasetId);
            ManagementTransactions.lockIssue(context, issueId);
            authority.require(context, actor, datasetId, "manage_metadata");
            var issue = ObjectSelect.query(Datasetissue.class).where(Datasetissue.T_ILI_TID.eq(issueId)).selectOne(context);
            var dataset = issue.getDataset1();
            if (!datasetId.equals(dataset.getTIliTid())) throw BusinessException.missing();
            if ("published".equals(dataset.getPublicationstatus())) authority.require(context, actor, datasetId, "publish");
            var schema = ObjectSelect.query(Dataschema.class).where(Dataschema.T_ILI_TID.eq(schemaId)).selectOne(context);
            if (schema == null || !"simple_csv".equals(schema.getAkind())) throw BusinessException.invalid("CSV-Schema fehlt.");
            var existing = dataset.getDeliveryconfigurations();
            if (existing.size() > 1) throw BusinessException.conflict("Mehrere Lieferkonfigurationen.");
            var config = existing.isEmpty() ? context.newObject(Deliveryconfiguration.class) : existing.getFirst();
            if (existing.isEmpty()) {
                config.setTIliTid(UUID.randomUUID());
                config.setTBasket(dataset.getTBasket());
                config.setDataset1(dataset);
            }
            config.setEnabled(enabled);
            config.setAformat("csv");
            config.setAllowedmodes(modes);
            issue.setAschema1(schema);
            return null;
        });
    }
}
