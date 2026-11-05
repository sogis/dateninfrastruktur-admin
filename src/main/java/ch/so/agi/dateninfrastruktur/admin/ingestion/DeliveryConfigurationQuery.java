package ch.so.agi.dateninfrastruktur.admin.ingestion;

import java.util.*;
import org.apache.cayenne.ObjectContext;
import ch.so.agi.dateninfrastruktur.admin.persistence.CayenneSql;

/** Fresh PG configuration plus the verified immutable technical bundle. No rights cache. */
public final class DeliveryConfigurationQuery {
    public record Configuration(long datasetKey,UUID datasetId,long schemaKey,UUID schemaId,UUID configurationId,long version,boolean enabled,boolean automaticPublication,String allowedModes,String definitionKey,String definitionSha256,String modelSetSha256,String fullSchemaDigest,String digest,CsvDefinitionRegistry.Definition definition){}
    private final CsvDefinitionRegistry registry;
    public DeliveryConfigurationQuery(CsvDefinitionRegistry registry){this.registry=registry;}
    public Configuration resolve(ObjectContext context,UUID dataset){
        var rows=CayenneSql.rows(context,"""
            SELECT d.t_id AS dataset_key,d.t_ili_tid AS dataset_id,s.t_id AS schema_key,s.t_ili_tid AS schema_id,
              c.t_ili_tid AS configuration_id,c.row_version,c.enabled,c.automaticpublication,c.allowedmodes,
              c.definitionkey,c.definitionsha256,s.modelname,s.modelversion,s.modelsha256
            FROM di_management.dataset d JOIN di_management.csvdeliveryconfiguration c ON c.dataset=d.t_id
            JOIN di_management.dataschema s ON s.t_id=c.aschema WHERE d.t_ili_tid=CAST(#bind($dataset) AS uuid)
            """,Map.of("dataset",dataset.toString()));
        if(rows.size()!=1)throw IngestionProblem.conflict("CONFIGURATION_CHANGED");
        var r=rows.getFirst();String key=r.get("definitionkey").toString(),hash=r.get("definitionsha256").toString();
        var approved=registry.resolve(key,hash);var definition=approved.definition();
        String primary=definition.modelFiles().stream().filter(f->f.relativePath().equals("models/"+definition.modelName()+".ili")).findFirst().orElseThrow().sha256();
        if(!definition.modelName().equals(r.get("modelname")) || !definition.modelVersion().equals(r.get("modelversion")) || !primary.equals(r.get("modelsha256")))throw IngestionProblem.conflict("CONFIGURATION_CHANGED");
        long schemaKey=((Number)r.get("schema_key")).longValue();
        var attrs=CayenneSql.rows(context,"SELECT aname,datatype,ordinal,mandatory,unit,codelist FROM di_management.attributedefinition WHERE aschema=#bind($schema) ORDER BY ordinal",Map.of("schema",schemaKey));
        if(attrs.size()!=definition.columns().size())throw IngestionProblem.conflict("CONFIGURATION_CHANGED");
        for(int i=0;i<attrs.size();i++){
            var a=attrs.get(i);var column=definition.columns().get(i);
            if(!column.attribute().equals(a.get("aname")) || !column.type().equals(a.get("datatype")) || ((Number)a.get("ordinal")).intValue()!=i+1 || !column.mandatory().equals(a.get("mandatory")) || a.get("unit")!=null || a.get("codelist")!=null)throw IngestionProblem.conflict("CONFIGURATION_CHANGED");
        }
        boolean enabled=Boolean.TRUE.equals(r.get("enabled")),automatic=Boolean.TRUE.equals(r.get("automaticpublication"));String modes=r.get("allowedmodes").toString();
        UUID schemaId=UUID.fromString(r.get("schema_id").toString()),configurationId=UUID.fromString(r.get("configuration_id").toString());long version=((Number)r.get("row_version")).longValue();
        String digest=CanonicalJson.digest(Map.ofEntries(Map.entry("schemaVersion",1),Map.entry("datasetId",dataset),Map.entry("configurationId",configurationId),Map.entry("configurationVersion",version),Map.entry("schemaId",schemaId),Map.entry("enabled",enabled),Map.entry("automaticPublication",automatic),Map.entry("allowedModes",modes),Map.entry("definitionKey",key),Map.entry("definitionSha256",hash),Map.entry("fullSchemaDigest",approved.fullSchemaDigest())));
        return new Configuration(((Number)r.get("dataset_key")).longValue(),dataset,schemaKey,schemaId,configurationId,version,enabled,automatic,modes,key,hash,definition.modelSetSha256(),approved.fullSchemaDigest(),digest,definition);
    }
}
