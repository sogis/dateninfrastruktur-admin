package ch.so.agi.dateninfrastruktur.admin.ingestion;

import java.util.*;
import org.apache.cayenne.ObjectContext;
import ch.so.agi.dateninfrastruktur.admin.persistence.CayenneSql;

/** Every target is server-derived; the browser supplies no storage or SQL identifier. */
public final class CsvExecutionPlanFactory {
    public record Input(long size,String sha256,String privateSpoolReference){}
    public record Output(UUID distributionId,String format,String objectKey,String fileName,String mediaType){}
    public record ClickHouse(String candidateDatabase,String candidateTable,String publicationName){}
    public record BaseSnapshot(UUID versionId,String csvObjectKey,String csvSha256,long csvSize,String dataSha256,long rowCount){}
    public record Runner(String runnerSha256,String repositorySha256,String imageSha256){
        boolean valid(){return ResultEvidenceValidator.hash(runnerSha256)&&ResultEvidenceValidator.hash(repositorySha256)&&ResultEvidenceValidator.hash(imageSha256);}
    }
    public record Plan(int schemaVersion,UUID submissionId,UUID executionId,long fence,UUID initiatorPrincipalId,UUID datasetId,UUID issueId,UUID reservedVersionId,String mode,UUID baseVersionId,String definitionKey,String definitionSha256,List<CsvDefinitionRegistry.FileHash> modelFiles,String modelSetSha256,String qualifiedTableName,CsvDefinitionRegistry.Dialect csvDialect,List<CsvDefinitionRegistry.Column> orderedColumns,List<String> rowKeyDefinition,String fullSchemaDigest,Input input,List<Output> outputs,ClickHouse clickhouse,CsvDefinitionRegistry.Limits limits,BaseSnapshot baseSnapshot,Runner runner){
        public Plan{modelFiles=List.copyOf(modelFiles);orderedColumns=List.copyOf(orderedColumns);rowKeyDefinition=List.copyOf(rowKeyDefinition);outputs=List.copyOf(outputs);}
    }
    public Plan create(ObjectContext context,Map<String,Object> row,UUID execution,long fence,Runner runner){
        var snapshot=CanonicalJson.JSON.readValue(row.get("configuration_snapshot").toString(),DeliveryConfigurationQuery.Configuration.class);var definition=snapshot.definition();
        UUID submission=SubmissionRepository.uuid(row,"id"),dataset=SubmissionRepository.uuid(row,"dataset_uuid"),issue=SubmissionRepository.uuid(row,"issue_uuid"),version=SubmissionRepository.uuid(row,"reserved_version_id");
        var outputs=new ArrayList<Output>();
        for(String format:List.of("csv","xlsx","parquet")){
            UUID id=UUID.randomUUID();String media=switch(format){case "csv"->"text/csv; charset=utf-8";case "xlsx"->"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";default->"application/vnd.apache.parquet";};
            outputs.add(new Output(id,format,"downloads/"+id+"/data."+format,"data."+format,media));
        }
        BaseSnapshot base=null;UUID baseVersion=SubmissionRepository.uuid(row,"base_version_id");
        if("append".equals(row.get("mode"))){
            if(baseVersion==null)throw IngestionProblem.conflict("PUBLICATION_CONFLICT");
            var previous=CayenneSql.rows(context,"""
                SELECT d.objectkey,d.sha256,d.bytesize,cp.evidence->>'dataSha256' AS data_sha256,(cp.evidence->>'rowCount')::bigint AS row_count
                FROM di_management.dataversion v JOIN di_management.distribution d ON d.version=v.t_id AND d.aformat='csv' AND d.availability='available'
                JOIN di_ingest.submission s ON s.published_version_id=v.t_ili_tid AND s.status='PUBLISHED'
                JOIN di_ingest.execution e ON e.submission_id=s.id AND e.state IN ('COMMITTED','FINISHED')
                JOIN di_ingest.execution_checkpoint cp ON cp.execution_id=e.id AND cp.checkpoint='SNAPSHOT_VALIDATED'
                WHERE v.t_ili_tid=CAST(#bind($base) AS uuid) AND v.availability='available' AND v.aschema=#bind($schema)
                  AND s.definition_sha256=#bind($definition) AND s.model_set_sha256=#bind($model)
                """,Map.of("base",baseVersion.toString(),"schema",snapshot.schemaKey(),"definition",snapshot.definitionSha256(),"model",snapshot.modelSetSha256()));
            if(previous.size()!=1 || previous.getFirst().get("data_sha256")==null)throw IngestionProblem.conflict("PUBLICATION_CONFLICT");
            var p=previous.getFirst();base=new BaseSnapshot(baseVersion,p.get("objectkey").toString(),p.get("sha256").toString(),SubmissionRepository.number(p,"bytesize"),p.get("data_sha256").toString(),SubmissionRepository.number(p,"row_count"));
        }
        String candidate="v_"+version.toString().replace("-","")+"_"+execution.toString().replace("-","");
        String publication="ds_"+dataset.toString().replace("-","")+"_i_"+issue.toString().replace("-","")+"_current";
        return new Plan(1,submission,execution,fence,SubmissionRepository.uuid(row,"submitted_by_uuid"),dataset,issue,version,row.get("mode").toString(),baseVersion,snapshot.definitionKey(),snapshot.definitionSha256(),definition.modelFiles(),snapshot.modelSetSha256(),definition.qualifiedTableName(),definition.dialect(),definition.columns(),definition.businessKey(),snapshot.fullSchemaDigest(),new Input(SubmissionRepository.number(row,"input_size"),row.get("input_sha256").toString(),row.get("private_spool_reference").toString()),outputs,new ClickHouse("di_data",candidate,publication),definition.limits(),base,runner);
    }
}
