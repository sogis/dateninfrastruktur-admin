package ch.so.agi.dateninfrastruktur.admin.ingestion;

import ch.so.agi.dateninfrastruktur.admin.audit.*;
import ch.so.agi.dateninfrastruktur.admin.persistence.*;
import java.util.*;
import org.apache.cayenne.ObjectContext;
import org.springframework.scheduling.annotation.Scheduled;

/** Derives current SQL from the present PG issue, never from a worker callback. */
public final class PublicationReconciler {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(PublicationReconciler.class);
    private long nextDiagnostic;
    private static final ActorContext ACTOR=new ActorContext("urn:di:operator","clickhouse-projector","service",null);
    private final AuditedUnitOfWork unit;
    private final ClickHousePublicationProjector projector;
    private final SubmissionRepository repository=new SubmissionRepository();
    record Pending(UUID submission,UUID dataset,UUID issue,UUID execution,UUID dispatch,long revision,String view){}
    record Desired(UUID version,String candidate,List<String> attributes,long count){}
    PublicationReconciler(AuditedUnitOfWork unit,ClickHousePublicationProjector projector){this.unit=unit;this.projector=projector;}
    @Scheduled(fixedDelay=3000)
    public synchronized void reconcilePending(){
        List<Pending> pending;
        try{pending=unit.read(context->CayenneSql.rows(context,"""
            SELECT s.id,d.t_ili_tid AS dataset,i.t_ili_tid AS issue,e.id AS execution,e.dispatch_id AS dispatch,s.publication_revision,e.plan->'clickhouse'->>'publicationName' AS view
            FROM di_ingest.submission s JOIN di_ingest.execution e ON e.submission_id=s.id AND e.state='COMMITTED'
            JOIN di_management.datasetissue i ON i.t_id=s.issue_id JOIN di_management.dataset d ON d.t_id=s.dataset_id
            WHERE s.status='COMMITTED' ORDER BY s.created_at,s.id LIMIT 16
            """,Map.of()).stream().map(r->new Pending(SubmissionRepository.uuid(r,"id"),SubmissionRepository.uuid(r,"dataset"),
                SubmissionRepository.uuid(r,"issue"),SubmissionRepository.uuid(r,"execution"),SubmissionRepository.uuid(r,"dispatch"),
                SubmissionRepository.number(r,"publication_revision"),r.get("view").toString())).toList());}
        catch(RuntimeException unavailable){return;}
        for(var job:pending){
            String phase="target";
            try{
                String view="ds_"+compact(job.dataset())+"_i_"+compact(job.issue())+"_current";
                if(!view.equals(job.view()))continue;
                Desired wanted=unit.read(context->desired(context,job));
                phase="clickhouse";
                projector.project(view,wanted.candidate(),wanted.attributes(),wanted.count());
                phase="postgresql";
                finish(job,wanted);
            }catch(InterruptedException interrupted){Thread.currentThread().interrupt();return;}
            catch(Exception unavailable){
                // Safe bounded diagnostic; never include remote response, SQL, credentials or stack traces.
                long now=System.nanoTime();
                if(nextDiagnostic==0||now-nextDiagnostic>=0){
                    String locations=Arrays.stream(unavailable.getStackTrace()).filter(f->f.getClassName().startsWith("ch.so.agi.dateninfrastruktur")||f.getClassName().startsWith("java.util")).limit(4).map(f->f.getClassName()+":"+f.getLineNumber()).collect(java.util.stream.Collectors.joining(","));
                    LOG.warn("CSV projection remains pending at {} ({}; {})",phase,unavailable.getClass().getSimpleName(),locations);
                    nextDiagnostic=now+java.util.concurrent.TimeUnit.MINUTES.toNanos(1);
                }
            }
        }
        removeArchivedViews();
    }

    private void removeArchivedViews(){
        // An archived issue is terminal in the catalog. Drop only its own derived name;
        // historic candidate tables and file distribution records remain untouched.
        List<Pending> retired;
        try{retired=unit.read(context->CayenneSql.rows(context,"""
            SELECT r.* FROM (
              SELECT DISTINCT ON (i.t_id) s.id,d.t_ili_tid AS dataset,i.t_ili_tid AS issue,e.id AS execution,
                e.dispatch_id AS dispatch,s.publication_revision,e.plan->'clickhouse'->>'publicationName' AS view
              FROM di_ingest.submission s JOIN di_ingest.execution e ON e.submission_id=s.id AND e.state='FINISHED'
              JOIN di_management.datasetissue i ON i.t_id=s.issue_id JOIN di_management.dataset d ON d.t_id=s.dataset_id
              WHERE s.status='PUBLISHED' AND (d.publicationstatus='archived' OR i.publicationstatus='archived')
              ORDER BY i.t_id,s.publication_revision DESC,s.id
            ) r WHERE NOT EXISTS(SELECT 1 FROM di_ingest.event v WHERE v.submission_id=r.id AND v.event_type='SQL_VIEW_REMOVED')
            LIMIT 8
            """,Map.of()).stream().map(r->new Pending(SubmissionRepository.uuid(r,"id"),SubmissionRepository.uuid(r,"dataset"),
                SubmissionRepository.uuid(r,"issue"),SubmissionRepository.uuid(r,"execution"),SubmissionRepository.uuid(r,"dispatch"),
                SubmissionRepository.number(r,"publication_revision"),r.get("view").toString())).toList());}
        catch(RuntimeException unavailable){return;}
        for(var job:retired)try{
            String name="ds_"+compact(job.dataset())+"_i_"+compact(job.issue())+"_current";
            if(!name.equals(job.view()))continue;
            projector.project(name,null,List.of(),0);
            unit.execute(ACTOR,new CommandContext(UUID.randomUUID(),UUID.randomUUID(),"ingestion.deproject","Archivierte SQL-Sicht entfernt"),context->{
                repository.lockDataset(context,job.dataset());
                var archived=CayenneSql.rows(context,"""
                    SELECT 1 FROM di_management.datasetissue i JOIN di_management.dataset d ON d.t_id=i.dataset
                    WHERE i.t_ili_tid=CAST(#bind($id) AS uuid) AND (d.publicationstatus='archived' OR i.publicationstatus='archived')
                    """,Map.of("id",job.issue().toString()));
                if(archived.size()==1)repository.event(context,job.submission(),job.execution(),"SQL_VIEW_REMOVED","Abgeleitete Sicht der archivierten Ausgabe entfernt.");
                return null;
            });
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();return;}
        catch(Exception unavailable){/* No historical objects are changed; removal remains pending. */}
    }

    private Desired desired(ObjectContext context,Pending job){
        var rows=CayenneSql.rows(context,"""
            SELECT v.t_ili_tid AS version,e.plan, v.objectcount
            FROM di_management.datasetissue i JOIN di_management.dataset d ON d.t_id=i.dataset
            LEFT JOIN di_management.dataversion v ON v.t_id=i.currentversion AND v.availability='available'
            LEFT JOIN di_ingest.submission s ON s.published_version_id=v.t_ili_tid AND s.status IN ('COMMITTED','PUBLISHED')
            LEFT JOIN di_ingest.execution e ON e.submission_id=s.id AND e.state IN ('COMMITTED','FINISHED')
            WHERE i.t_ili_tid=CAST(#bind($issue) AS uuid) AND d.t_ili_tid=CAST(#bind($dataset) AS uuid)
              AND d.publicationstatus='published' AND i.publicationstatus='published'
            """,Map.of("issue",job.issue().toString(),"dataset",job.dataset().toString()));
        if(rows.isEmpty()||rows.getFirst().get("plan")==null)return new Desired(null,null,List.of(),0);
        if(rows.size()!=1)throw IngestionProblem.conflict("PUBLICATION_CONFLICT");
        var row=rows.getFirst();var plan=CanonicalJson.JSON.readValue(row.get("plan").toString(),CsvExecutionPlanFactory.Plan.class);
        UUID version=SubmissionRepository.uuid(row,"version");
        String candidate="v_"+compact(version)+"_"+compact(plan.executionId());
        if(!version.equals(plan.reservedVersionId())||!job.issue().equals(plan.issueId())||!job.dataset().equals(plan.datasetId())||
            !"di_data".equals(plan.clickhouse().candidateDatabase())||!candidate.equals(plan.clickhouse().candidateTable()))throw IngestionProblem.conflict("PUBLICATION_CONFLICT");
        return new Desired(version,candidate,plan.orderedColumns().stream().map(CsvDefinitionRegistry.Column::attribute).toList(),SubmissionRepository.number(row,"objectcount"));
    }
    private void finish(Pending job,Desired projected){
        var command=new CommandContext(UUID.randomUUID(),UUID.randomUUID(),"ingestion.project","Verifizierte SQL-Projektion");
        unit.execute(ACTOR,command,context->{
            repository.lockDataset(context,job.dataset());var row=repository.find(context,job.submission(),true);
            if(!"COMMITTED".equals(row.get("status"))||SubmissionRepository.number(row,"publication_revision")!=job.revision()||!projected.equals(desired(context,job)))return null;
            var slots=CayenneSql.rows(context,"SELECT state FROM di_ingest.dataset_execution_slot WHERE submission_id=CAST(#bind($id) AS uuid) AND dispatch_id=CAST(#bind($dispatch) AS uuid) FOR UPDATE",Map.of("id",job.submission().toString(),"dispatch",job.dispatch().toString()));
            if(slots.size()!=1||!"COMMITTED".equals(slots.getFirst().get("state")))throw IngestionProblem.conflict("STALE_EXECUTION");
            repository.transition(context,job.submission(),"COMMITTED","PUBLISHED",null,projected.candidate()==null?"Angenommen; aktuell keine SQL-Sicht.":"Veröffentlicht; SQL-Sicht überprüft.");
            SubmissionRepository.execute(context,"UPDATE di_ingest.execution SET state='FINISHED' WHERE id=CAST(#bind($id) AS uuid) AND state='COMMITTED'",Map.of("id",job.execution().toString()));
            SubmissionRepository.execute(context,"UPDATE di_ingest.dataset_execution_slot SET state='IDLE',submission_id=NULL,dispatch_id=NULL WHERE dispatch_id=CAST(#bind($id) AS uuid) AND state='COMMITTED'",Map.of("id",job.dispatch().toString()));
            repository.event(context,job.submission(),job.execution(),"PUBLISHED","SQL-Ziel nach aktueller PostgreSQL-Revision "+job.revision()+" verifiziert.");
            return null;
        });
    }
    private static String compact(UUID id){return id.toString().replace("-","");}
}
