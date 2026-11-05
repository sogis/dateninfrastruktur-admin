package ch.so.agi.dateninfrastruktur.admin.ingestion;

import java.util.*;

/** Typed attestations of the trusted runner, bound to the immutable server plan. */
public final class ResultEvidenceValidator {
    public record InputEvidence(int schemaVersion,long size,String sha256){}
    public record ValidationEvidence(int schemaVersion,String definitionSha256,String modelSetSha256,String validatorVersion,boolean valid,long rowCount,String dataSha256){}
    public record CandidateEvidence(int schemaVersion,String database,String table,String schemaDigest,long rowCount,String dataSha256){}
    public record Artifact(UUID distributionId,String format,String objectKey,String mediaType,long byteSize,String sha256,String snapshotDigest){}
    public record ArtifactsEvidence(int schemaVersion,List<Artifact> artifacts,String runnerSha256,String repositorySha256,String imageSha256){public ArtifactsEvidence{artifacts=List.copyOf(artifacts);}}
    public record Manifest(int schemaVersion,UUID submissionId,UUID executionId,long fence,String planDigest,InputEvidence input,ValidationEvidence incoming,ValidationEvidence snapshot,CandidateEvidence candidate,ArtifactsEvidence outputs){}
    public void input(CsvExecutionPlanFactory.Plan plan,InputEvidence e){
        require(e!=null && e.schemaVersion()==1 && e.size()==plan.input().size() && Objects.equals(e.sha256(),plan.input().sha256()),"INPUT_MISMATCH");
    }
    public void validation(CsvExecutionPlanFactory.Plan plan,ValidationEvidence e,boolean snapshot){
        require(e!=null && e.schemaVersion()==1 && e.valid() && "1.15.0".equals(e.validatorVersion()) && Objects.equals(e.definitionSha256(),plan.definitionSha256()) && Objects.equals(e.modelSetSha256(),plan.modelSetSha256()) && hash(e.dataSha256()) && e.rowCount()>0 && e.rowCount()<=plan.limits().maxRows(),"VALIDATION_FAILED");
    }
    public void candidate(CsvExecutionPlanFactory.Plan plan,ValidationEvidence snapshot,CandidateEvidence e){
        require(e!=null && e.schemaVersion()==1 && Objects.equals(e.database(),plan.clickhouse().candidateDatabase()) && Objects.equals(e.table(),plan.clickhouse().candidateTable()) && Objects.equals(e.schemaDigest(),plan.fullSchemaDigest()) && e.rowCount()==snapshot.rowCount() && Objects.equals(e.dataSha256(),snapshot.dataSha256()),"ARTIFACT_CONFLICT");
    }
    public void artifacts(CsvExecutionPlanFactory.Plan plan,ValidationEvidence snapshot,ArtifactsEvidence e){
        require(e!=null && e.schemaVersion()==1 && plan.runner()!=null && Objects.equals(e.runnerSha256(),plan.runner().runnerSha256()) && Objects.equals(e.repositorySha256(),plan.runner().repositorySha256()) && Objects.equals(e.imageSha256(),plan.runner().imageSha256()) && e.artifacts().size()==3,"ARTIFACT_CONFLICT");
        var seen=new HashSet<UUID>();
        for(var artifact:e.artifacts()){
            require(artifact!=null && seen.add(artifact.distributionId()) && artifact.byteSize()>0 && artifact.byteSize()<=10737418240L && hash(artifact.sha256()) && Objects.equals(artifact.snapshotDigest(),snapshot.dataSha256()),"ARTIFACT_CONFLICT");
            var output=plan.outputs().stream().filter(p->p.distributionId().equals(artifact.distributionId())).findFirst().orElseThrow(()->IngestionProblem.conflict("ARTIFACT_CONFLICT"));
            require(output.format().equals(artifact.format()) && output.objectKey().equals(artifact.objectKey()) && output.mediaType().equals(artifact.mediaType()),"ARTIFACT_CONFLICT");
        }
    }
    public static final class VerifiedResult {
        private final CsvExecutionPlanFactory.Plan plan;private final Manifest manifest;
        private VerifiedResult(CsvExecutionPlanFactory.Plan plan,Manifest manifest){this.plan=plan;this.manifest=manifest;}
        public CsvExecutionPlanFactory.Plan plan(){return plan;}public Manifest manifest(){return manifest;}
    }
    public VerifiedResult validate(CsvExecutionPlanFactory.Plan plan,String digest,Manifest result){
        require(result!=null && result.schemaVersion()==1 && plan.submissionId().equals(result.submissionId()) && plan.executionId().equals(result.executionId()) && plan.fence()==result.fence() && digest.equals(result.planDigest()),"STALE_EXECUTION");
        input(plan,result.input());validation(plan,result.incoming(),false);validation(plan,result.snapshot(),true);
        long expected=result.incoming().rowCount()+("append".equals(plan.mode())?plan.baseSnapshot().rowCount():0);
        require(expected==result.snapshot().rowCount(),"VALIDATION_FAILED");
        if("replace".equals(plan.mode()))require(result.incoming().dataSha256().equals(result.snapshot().dataSha256()),"VALIDATION_FAILED");
        candidate(plan,result.snapshot(),result.candidate());artifacts(plan,result.snapshot(),result.outputs());return new VerifiedResult(plan,result);
    }
    public static boolean hash(String value){return value!=null && value.matches("[0-9a-f]{64}");}
    private static void require(boolean valid,String code){if(!valid)throw IngestionProblem.conflict(code);}
}
