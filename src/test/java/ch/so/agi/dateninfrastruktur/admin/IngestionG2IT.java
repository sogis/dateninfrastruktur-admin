package ch.so.agi.dateninfrastruktur.admin;

import java.util.*;
import java.time.Instant;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.apache.cayenne.query.ObjectSelect;
import ch.so.agi.dateninfrastruktur.admin.audit.*;
import ch.so.agi.dateninfrastruktur.admin.model.*;
import ch.so.agi.dateninfrastruktur.admin.persistence.*;
import ch.so.agi.dateninfrastruktur.admin.identity.PrincipalRepository;
import ch.so.agi.dateninfrastruktur.admin.ingestion.*;
import ch.so.agi.dateninfrastruktur.admin.catalog.DeliveryRegistrationService;

/** Real PG/Cayenne process contract. Evidence here is explicitly synthetic trusted-runner attestation. */
public final class IngestionG2IT {
    static final java.util.concurrent.atomic.AtomicLong RUNS=new java.util.concurrent.atomic.AtomicLong(System.nanoTime());
    static CommandContext command(String action){return ManagementFixture.command(action);}
    static final class Fixture implements AutoCloseable {
        final ManagementFixture f=new ManagementFixture();final CsvDefinitionRegistry registry=new CsvDefinitionRegistry();
        final DeliveryConfigurationQuery configs=new DeliveryConfigurationQuery(registry);
        final SubmissionAuthorizationService users=new SubmissionAuthorizationService(f.runtime,f.authority,configs);
        final IdentityProvisioningService identities=new IdentityProvisioningService(f.unit);
        final SubmitterAdministrationService managers=new SubmitterAdministrationService(f.unit,f.authority,users);
        final SpoolProof proof=new SpoolProof("isolated-plugin-secret-000000000000000000000000");
        final SubmissionService submissions=new SubmissionService(f.unit,users,proof,104857600);
        final SubmissionQueryService queries=new SubmissionQueryService(f.unit,users);
        final IngestionRunnerAuthorization runners=new IngestionRunnerAuthorization();
        final String instance="1234567890abcdef1234567890abcdef";
        final DispatchService dispatches=new DispatchService(f.unit,users,runners,instance);
        final ExecutionService executions=new ExecutionService(f.unit,users,runners,new CsvExecutionPlanFactory(),instance,new byte[32]);
        final PublicationService publications=new PublicationService(f.unit,executions,new DeliveryRegistrationService(f.unit,f.authority));
        final UUID dataset,issue,userId,grant;final ActorContext runner;
        Fixture(){
            userId=identities.ensureUser(f.anna);identities.ensureUser(f.ben);
            runner=new ActorContext(f.manager.issuer(),"executor-"+UUID.randomUUID(),"service",null);
            f.unit.execute(f.manager,command("test.bootstrap"),ctx->{
                var manager=new PrincipalRepository().requireActive(ctx,f.manager);var office=ObjectSelect.query(Office.class).where(Office.T_ILI_TID.eq(f.office)).selectOne(ctx);
                var capability=ctx.newObject(Authorityassignment.class);capability.setTIliTid(UUID.randomUUID());capability.setCapability("manage_submitters");capability.setPrincipal1(manager);capability.setAssignedby1(manager);capability.setOffice1(office);capability.setValidfrom(CayenneSql.now(ctx).minusDays(1));capability.setReason("Isolated test bootstrap");
                var service=ctx.newObject(Principal.class);service.setTIliTid(UUID.randomUUID());service.setAissuer(runner.issuer());service.setAsubject(runner.subject());service.setAkind("service");service.setAactive(true);service.setProvisionedat(CayenneSql.now(ctx));return null;
            });
            dataset=f.published();issue=f.unit.read(ctx->SubmissionRepository.uuid(CayenneSql.rows(ctx,"SELECT i.t_ili_tid FROM di_management.datasetissue i JOIN di_management.dataset d ON d.t_id=i.dataset WHERE d.t_ili_tid=CAST(#bind($id) AS uuid)",Map.of("id",dataset.toString())).getFirst(),"t_ili_tid"));
            new DeliveryConfigurationService(f.unit,f.authority,registry).update(f.manager,command("csv.configure"),dataset,new DeliveryConfigurationService.Update(version("dataset",dataset),true,true,"population-v1","replace_and_append",null));
            grant=managers.grant(f.manager,command("submitter.grant"),new SubmitterAdministrationService.Grant(new SubmitterAdministrationService.Scope(null,dataset),userId,version("dataset",dataset),null,null,"Testauftrag"));
        }
        long version(String table,UUID id){if(!Set.of("dataset","authorityassignment","office").contains(table))throw new IllegalArgumentException();return f.unit.read(ctx->((Number)CayenneSql.rows(ctx,"SELECT row_version FROM di_management."+table+" WHERE t_ili_tid=CAST(#bind($id) AS uuid)",Map.of("id",id.toString())).getFirst().get("row_version")).longValue());}
        UUID ready(){String digest=f.unit.read(ctx->configs.resolve(ctx,dataset).digest());var id=submissions.create(f.anna,command("ingestion.create"),new SubmissionService.Create(dataset,issue,"replace",digest,"Prüfung"));String hash="1".repeat(64),name="daten.csv",spool="uploads/"+id+"/input.csv";submissions.seal(f.anna,command("ingestion.seal"),id,new SubmissionService.Seal(42,hash,name,spool,proof.sign(id,42,hash,name,spool)));return id;}
        ExecutionService.Claimed claim(UUID id){var dispatch=dispatches.reserve(runner,command("ingestion.reserve"),new DispatchService.Request(id));return executions.claim(runner,command("ingestion.claim"),new ExecutionService.Claim(dispatch.dispatchId(),instance,DispatchService.JOB,Long.toString(RUNS.incrementAndGet())));}
        ExecutionService.Binding binding(ExecutionService.Claimed claim){return new ExecutionService.Binding(claim.fence(),claim.leaseSecret());}
        ResultEvidenceValidator.Manifest evidence(ExecutionService.Claimed claim){
            var p=claim.plan();String data="2".repeat(64);var input=new ResultEvidenceValidator.InputEvidence(1,p.input().size(),p.input().sha256());var valid=new ResultEvidenceValidator.ValidationEvidence(1,p.definitionSha256(),p.modelSetSha256(),"1.15.0",true,2,data);
            var candidate=new ResultEvidenceValidator.CandidateEvidence(1,p.clickhouse().candidateDatabase(),p.clickhouse().candidateTable(),p.fullSchemaDigest(),2,data);
            var artifacts=new ResultEvidenceValidator.ArtifactsEvidence(1,p.outputs().stream().map(o->new ResultEvidenceValidator.Artifact(o.distributionId(),o.format(),o.objectKey(),o.mediaType(),123,"3".repeat(64),data)).toList(),"4".repeat(64),"5".repeat(64),"6".repeat(64));
            return new ResultEvidenceValidator.Manifest(1,p.submissionId(),p.executionId(),p.fence(),claim.planDigest(),input,valid,valid,candidate,artifacts);
        }
        ResultEvidenceValidator.Manifest validated(ExecutionService.Claimed claim){var e=evidence(claim);List<Object> values=List.of(e.input(),e.incoming(),e.snapshot(),e.candidate(),e.outputs());var names=List.of("INPUT_VERIFIED","INCOMING_VALIDATED","SNAPSHOT_VALIDATED","CANDIDATE_VERIFIED","ARTIFACTS_VERIFIED");for(int i=0;i<5;i++)executions.checkpoint(runner,command("ingestion.checkpoint"),claim.executionId(),binding(claim),names.get(i),values.get(i));return e;}
        void revoke(){managers.revoke(f.manager,command("submitter.revoke"),grant,version("authorityassignment",grant),"Lieferrecht entzogen");}
        public void close(){f.close();}
    }
    @Test void idempotencyScopeAndExclusiveClaim()throws Exception{
        try(var x=new Fixture()){
            assertEquals(1,x.users.listEligible(x.f.anna,new SubmissionAuthorizationService.PageRequest(0,25,"")).size());assertTrue(x.users.listEligible(x.f.ben,new SubmissionAuthorizationService.PageRequest(0,25,"")).isEmpty());
            var id=x.ready();assertThrows(IngestionProblem.class,()->x.queries.detail(x.f.ben,id));assertEquals("READY",x.queries.detail(x.f.anna,id).state());assertEquals("READY",x.queries.detail(x.f.manager,id).state());
            var request=new DispatchService.Request(id);var command=command("ingestion.reserve");var dispatch=x.dispatches.reserve(x.runner,command,request);assertEquals(dispatch,x.dispatches.reserve(x.runner,command,request));
            try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
                var start=new CountDownLatch(1);List<Future<ExecutionService.Claimed>> calls=new ArrayList<>();for(String run:List.of("1","2"))calls.add(pool.submit(()->{start.await();return x.executions.claim(x.runner,command("ingestion.claim"),new ExecutionService.Claim(dispatch.dispatchId(),x.instance,DispatchService.JOB,run));}));start.countDown();int won=0,denied=0;ExecutionService.Claimed winner=null;
                for(var call:calls)try{winner=call.get(20,TimeUnit.SECONDS);won++;}catch(ExecutionException rejected){assertInstanceOf(IngestionProblem.class,rejected.getCause());denied++;}assertEquals(1,won);assertEquals(1,denied);
                var claimed=winner;assertNotNull(claimed);var binding=x.binding(claimed);assertThrows(IngestionProblem.class,()->x.executions.heartbeat(x.f.anna,command("ingestion.heartbeat"),claimed.executionId(),binding));assertThrows(IngestionProblem.class,()->x.executions.heartbeat(x.runner,command("ingestion.heartbeat"),claimed.executionId(),new ExecutionService.Binding(claimed.fence()+1,claimed.leaseSecret())));
                assertThrows(IngestionProblem.class,()->x.executions.checkpoint(x.runner,command("ingestion.checkpoint"),claimed.executionId(),binding,"CANDIDATE_VERIFIED",x.evidence(claimed).candidate()));
                var e=x.evidence(claimed);x.executions.checkpoint(x.runner,command("ingestion.checkpoint"),claimed.executionId(),binding,"INPUT_VERIFIED",e.input());x.executions.checkpoint(x.runner,command("ingestion.checkpoint"),claimed.executionId(),binding,"INPUT_VERIFIED",e.input());
                x.executions.fail(x.runner,command("ingestion.fail"),claimed.executionId(),binding,"DEPENDENCY_UNAVAILABLE");assertEquals("FAILED_RETRYABLE",x.queries.detail(x.f.anna,id).state());
                x.submissions.retry(x.f.anna,command("ingestion.retry"),id,x.queries.detail(x.f.anna,id).statusVersion(),"Bewusster Versuch");var retry=x.claim(id);assertTrue(retry.fence()>claimed.fence());assertNotEquals(retry.plan().outputs(),claimed.plan().outputs());assertNotEquals(retry.plan().clickhouse().candidateTable(),claimed.plan().clickhouse().candidateTable());assertThrows(IngestionProblem.class,()->x.executions.heartbeat(x.runner,command("ingestion.heartbeat"),claimed.executionId(),binding));
            }
            x.revoke();assertTrue(x.users.listEligible(x.f.anna,new SubmissionAuthorizationService.PageRequest(0,25,"")).isEmpty());assertNotNull(x.queries.detail(x.f.anna,id));x.submissions.cancel(x.f.anna,command("ingestion.cancel"),id,x.queries.detail(x.f.anna,id).statusVersion(),"Nach Entzug abbrechen");assertEquals("CANCELLED",x.queries.detail(x.f.anna,id).state());
        }
    }
    @Test void submissionKeyAndSealBindExactInput(){try(var x=new Fixture()){
        String digest=x.f.unit.read(ctx->x.configs.resolve(ctx,x.dataset).digest());var input=new SubmissionService.Create(x.dataset,x.issue,"replace",digest,"same");var key=command("ingestion.create");var id=x.submissions.create(x.f.anna,key,input);assertEquals(id,x.submissions.create(x.f.anna,key,input));
        assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(IngestionProblem.class,()->x.submissions.create(x.f.anna,key,new SubmissionService.Create(x.dataset,x.issue,"replace",digest,"different"))).code());
        assertEquals("ISSUE_NOT_AVAILABLE",assertThrows(IngestionProblem.class,()->x.submissions.create(x.f.anna,command("ingestion.create"),new SubmissionService.Create(x.dataset,UUID.randomUUID(),"replace",digest,"wrong issue"))).code());
        String spool="uploads/"+id+"/input.csv",sha="1".repeat(64),name="file.csv";var seal=new SubmissionService.Seal(42,sha,name,spool,x.proof.sign(id,42,sha,name,spool));assertThrows(IngestionProblem.class,()->x.submissions.seal(x.f.ben,command("ingestion.seal"),id,seal));
        assertThrows(IngestionProblem.class,()->x.submissions.seal(x.f.anna,command("ingestion.seal"),id,new SubmissionService.Seal(42,sha,name,spool,"0".repeat(64))));
        var command=command("ingestion.seal");assertEquals(id,x.submissions.seal(x.f.anna,command,id,seal));assertEquals(id,x.submissions.seal(x.f.anna,command,id,seal));
        String changed="2".repeat(64);assertThrows(IngestionProblem.class,()->x.submissions.seal(x.f.anna,command("ingestion.seal"),id,new SubmissionService.Seal(42,changed,name,spool,x.proof.sign(id,42,changed,name,spool))));assertEquals("READY",x.queries.detail(x.f.anna,id).state());
    }}
    @Test void atomicAcceptanceAndLostAcknowledgement(){try(var x=new Fixture()){
        var id=x.ready();var claim=x.claim(id);var waiting=x.ready();var manifest=x.validated(claim);var command=command("ingestion.complete");var accepted=x.publications.complete(x.runner,command,claim.executionId(),x.binding(claim),manifest);assertEquals("COMMITTED",accepted.state());assertEquals(accepted,x.publications.complete(x.runner,command("ingestion.complete"),claim.executionId(),x.binding(claim),manifest));
        var target=new ch.so.agi.dateninfrastruktur.admin.authorization.DownloadPathResolver.DownloadTarget(claim.plan().outputs().getFirst().distributionId(),"data.csv","GET");assertEquals(403,new ch.so.agi.dateninfrastruktur.admin.authorization.JdbcAuthorizationRepository(x.f.authz).evaluate(Optional.of(x.f.anna),target).status());
        assertEquals(3,x.f.count("SELECT count(*) FROM di_management.distribution d JOIN di_management.dataversion v ON v.t_id=d.version WHERE v.t_ili_tid=?",accepted.versionId()));assertEquals(1,x.f.count("SELECT count(*) FROM di_management.catalogaction a JOIN di_management.dataversion v ON v.t_id=a.affectedversion WHERE v.t_ili_tid=? AND a.actioncode='accept_delivery' AND a.initiator IS NOT NULL",accepted.versionId()));
        assertEquals(1,x.f.count("SELECT count(*) FROM di_management.datasetissue i JOIN di_management.dataversion v ON v.t_id=i.currentversion WHERE v.t_ili_tid=?",accepted.versionId()));
        assertTrue(x.f.count("SELECT count(*) FROM audit.row_change WHERE object_oid=CAST(? AS text)",accepted.versionId())>0);
        x.revoke();assertEquals(accepted,x.publications.complete(x.runner,command("ingestion.complete"),claim.executionId(),x.binding(claim),manifest));assertThrows(IngestionProblem.class,()->x.executions.fail(x.runner,command("ingestion.fail"),claim.executionId(),x.binding(claim),"DEPENDENCY_UNAVAILABLE"));assertEquals("COMMITTED",x.queries.detail(x.f.anna,id).state());assertEquals("DATASET_BUSY",assertThrows(IngestionProblem.class,()->x.dispatches.reserve(x.runner,command("ingestion.reserve"),new DispatchService.Request(waiting))).code());
    }}
    @Test void committedRevocationBlocksAcceptance(){try(var x=new Fixture()){
        var id=x.ready();var claim=x.claim(id);var manifest=x.validated(claim);x.revoke();assertThrows(IngestionProblem.class,()->x.publications.complete(x.runner,command("ingestion.complete"),claim.executionId(),x.binding(claim),manifest));assertEquals("REJECTED_AUTHORIZATION",x.queries.detail(x.f.anna,id).state());assertEquals(0,x.f.count("SELECT count(*) FROM di_management.dataversion WHERE t_ili_tid=?",claim.plan().reservedVersionId()));
    }}
    @Test void officeGroupMembershipExpiryAndDelegation(){try(var x=new Fixture()){
        var limited=new ActorContext(x.f.manager.issuer(),"supplier-manager-"+UUID.randomUUID(),"user",null);x.identities.ensureUser(limited);
        var bootstrap=new ch.so.agi.dateninfrastruktur.admin.operator.DeliveryAuthorityBootstrapCommand.Request(1,UUID.randomUUID(),x.f.office,null,limited.issuer(),limited.subject(),"Only delivery management");
        var assignment=ch.so.agi.dateninfrastruktur.admin.operator.DeliveryAuthorityBootstrapCommand.bootstrap(x.f.unit,x.runner,bootstrap);assertEquals(assignment,ch.so.agi.dateninfrastruktur.admin.operator.DeliveryAuthorityBootstrapCommand.bootstrap(x.f.unit,x.runner,bootstrap));
        x.f.unit.read(ctx->{assertTrue(x.f.authority.allowed(ctx,limited,x.dataset,"manage_submitters"));for(String cap:List.of("publish","manage_access","manage_metadata","submit_data"))assertFalse(x.f.authority.allowed(ctx,limited,x.dataset,cap));return null;});
        assertThrows(ch.so.agi.dateninfrastruktur.admin.access.BusinessException.class,()->x.f.catalog.createDraft(limited,command("create_draft"),new ch.so.agi.dateninfrastruktur.admin.catalog.CatalogCommandService.Create(x.f.office,"escalation-"+UUID.randomUUID(),"standalone",ManagementFixture.metadata("No"))));
        UUID groupId=UUID.randomUUID(),membershipId=UUID.randomUUID();
        x.f.unit.execute(x.f.manager,command("test.group"),ctx->{var manager=new PrincipalRepository().requireActive(ctx,x.f.manager);var anna=new PrincipalRepository().requireActive(ctx,x.f.anna);var group=ctx.newObject(Principal.class);group.setTIliTid(groupId);group.setAissuer("urn:di:local-group");group.setAsubject(UUID.randomUUID().toString());group.setAkind("group");group.setAactive(true);group.setProvisionedat(CayenneSql.now(ctx));var membership=ctx.newObject(Groupmembership.class);membership.setTIliTid(membershipId);membership.setAgroup1(group);membership.setAmember1(anna);membership.setValidfrom(CayenneSql.now(ctx).minusDays(1));membership.setReason("Test membership");return null;});
        var scope=new SubmitterAdministrationService.Scope(x.f.office,null);x.managers.grant(limited,command("submitter.grant"),new SubmitterAdministrationService.Grant(scope,groupId,x.version("office",x.f.office),null,null,"Office group submission"));
        x.revoke();assertEquals(1,x.users.listEligible(x.f.anna,new SubmissionAuthorizationService.PageRequest(0,25,"")).size());var groupedId=x.ready();var groupedClaim=x.claim(groupedId);var groupedEvidence=x.validated(groupedClaim);
        UUID future=x.f.published();assertTrue(x.f.unit.<Boolean>read(ctx->x.f.authority.allowed(ctx,x.f.anna,future,"submit_data")));assertFalse(x.f.unit.<Boolean>read(ctx->x.f.authority.allowed(ctx,x.f.anna,x.dataset,"publish")));
        x.f.unit.execute(x.f.manager,command("test.revoke_membership"),ctx->{var m=ObjectSelect.query(Groupmembership.class).where(Groupmembership.T_ILI_TID.eq(membershipId)).selectOne(ctx);m.setRevokedat(CayenneSql.now(ctx));m.setRevokedby1(new PrincipalRepository().requireActive(ctx,x.f.manager));m.setReason("Revoked membership");return null;});assertTrue(x.users.listEligible(x.f.anna,new SubmissionAuthorizationService.PageRequest(0,25,"")).isEmpty());assertThrows(IngestionProblem.class,()->x.publications.complete(x.runner,command("ingestion.complete"),groupedClaim.executionId(),x.binding(groupedClaim),groupedEvidence));assertEquals("REJECTED_AUTHORIZATION",x.queries.detail(x.f.anna,groupedId).state());
        var timed=x.managers.grant(limited,command("submitter.grant"),new SubmitterAdministrationService.Grant(new SubmitterAdministrationService.Scope(null,x.dataset),x.userId,x.version("dataset",x.dataset),Instant.now().plusSeconds(3600),Instant.now().plusSeconds(7200),"Not yet valid"));assertTrue(x.users.listEligible(x.f.anna,new SubmissionAuthorizationService.PageRequest(0,25,"")).isEmpty());
        x.f.unit.execute(x.f.manager,command("test.disable"),ctx->{new PrincipalRepository().requireActive(ctx,x.f.anna).setAactive(false);return null;});assertEquals("PRINCIPAL_INACTIVE",assertThrows(IngestionProblem.class,()->x.users.listEligible(x.f.anna,new SubmissionAuthorizationService.PageRequest(0,25,"")).size()).code());
        assertThrows(IngestionProblem.class,()->x.managers.grant(x.f.ben,command("submitter.grant"),new SubmitterAdministrationService.Grant(scope,groupId,x.version("office",x.f.office),null,null,"No delegation")));
        var foreignOffice=UUID.randomUUID();assertThrows(IngestionProblem.class,()->x.managers.grant(limited,command("submitter.grant"),new SubmitterAdministrationService.Grant(new SubmitterAdministrationService.Scope(foreignOffice,null),groupId,1,null,null,"Foreign office")));
    }}
    @Test void disabledOriginalPrincipalCannotFinalize(){try(var x=new Fixture()){
        var id=x.ready();var claim=x.claim(id);var evidence=x.validated(claim);x.f.unit.execute(x.f.manager,command("test.disable_before_commit"),ctx->{new PrincipalRepository().requireActive(ctx,x.f.anna).setAactive(false);return null;});
        assertEquals("PRINCIPAL_INACTIVE",assertThrows(IngestionProblem.class,()->x.publications.complete(x.runner,command("ingestion.complete"),claim.executionId(),x.binding(claim),evidence)).code());assertEquals("REJECTED_AUTHORIZATION",x.queries.detail(x.f.manager,id).state());assertEquals(0,x.f.count("SELECT count(*) FROM di_management.dataversion WHERE t_ili_tid=?",claim.plan().reservedVersionId()));
    }}
    @Test void guardOrdersConcurrentRevocation()throws Exception{
        try(var x=new Fixture();var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var id=x.ready();var claim=x.claim(id);var manifest=x.validated(claim);
            var guarded=new CountDownLatch(1);var release=new CountDownLatch(1);var revoking=new CountDownLatch(1);
            var commit=pool.submit(()->x.f.unit.executeFinalization(x.runner,command("test.acceptance_guard"),ctx->{guarded.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("Release timeout");}catch(InterruptedException e){throw new AssertionError(e);}return x.publications.complete(x.runner,command("ingestion.complete"),claim.executionId(),x.binding(claim),manifest);}));
            assertTrue(guarded.await(10,TimeUnit.SECONDS));var revoke=pool.submit(()->{revoking.countDown();x.revoke();return true;});assertTrue(revoking.await(10,TimeUnit.SECONDS));
            assertThrows(TimeoutException.class,()->revoke.get(200,TimeUnit.MILLISECONDS));release.countDown();assertEquals("COMMITTED",commit.get(10,TimeUnit.SECONDS).state());assertTrue(revoke.get(10,TimeUnit.SECONDS));assertEquals("COMMITTED",x.queries.detail(x.f.anna,id).state());
        }
        try(var x=new Fixture();var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var id=x.ready();var claim=x.claim(id);var manifest=x.validated(claim);var guarded=new CountDownLatch(1);var release=new CountDownLatch(1);
            long expected=x.version("authorityassignment",x.grant);var revoke=pool.submit(()->x.f.unit.execute(x.f.manager,command("test.revocation_guard"),ctx->{x.managers.revoke(x.f.manager,command("submitter.revoke"),x.grant,expected,"Concurrent revoke");guarded.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("Release timeout");}catch(InterruptedException e){throw new AssertionError(e);}return true;}));
            assertTrue(guarded.await(10,TimeUnit.SECONDS));var commit=pool.submit(()->x.publications.complete(x.runner,command("ingestion.complete"),claim.executionId(),x.binding(claim),manifest));assertThrows(TimeoutException.class,()->commit.get(200,TimeUnit.MILLISECONDS));release.countDown();assertTrue(revoke.get(10,TimeUnit.SECONDS));var denied=assertThrows(ExecutionException.class,()->commit.get(10,TimeUnit.SECONDS));assertInstanceOf(IngestionProblem.class,denied.getCause());assertEquals("REJECTED_AUTHORIZATION",x.queries.detail(x.f.anna,id).state());
        }
    }
    @Test void flushedAcceptanceRollsBackAndDoesNotLeakActor()throws Exception{try(var x=new Fixture()){
        var id=x.ready();var claim=x.claim(id);var manifest=x.validated(claim);
        assertThrows(IllegalStateException.class,()->x.f.unit.executeFinalization(x.runner,command("test.rollback"),ctx->{x.publications.complete(x.runner,command("ingestion.complete"),claim.executionId(),x.binding(claim),manifest);throw new IllegalStateException("Synthetic failure after Cayenne flush");}));
        assertEquals("READY_TO_COMMIT",x.queries.detail(x.f.anna,id).state());assertEquals(0,x.f.count("SELECT count(*) FROM di_management.dataversion WHERE t_ili_tid=?",claim.plan().reservedVersionId()));assertEquals(0,x.f.count("SELECT count(*) FROM audit.row_change WHERE command_id=?",id));
        for(int i=0;i<12;i++)try(var c=x.f.writer.getConnection();var statement=c.createStatement();var result=statement.executeQuery("SELECT nullif(current_setting('di.actor_subject',true),''),nullif(current_setting('di.command_id',true),'')")){assertTrue(result.next());assertNull(result.getString(1));assertNull(result.getString(2));}
        var accepted=x.publications.complete(x.runner,command("ingestion.complete"),claim.executionId(),x.binding(claim),manifest);assertEquals("COMMITTED",accepted.state());
        try(var c=x.f.admin.getConnection();var ps=c.prepareStatement("SELECT count(DISTINCT transaction_id),bool_and(actor_subject=?),bool_and(command_id=?) FROM audit.row_change WHERE command_id=?")){ps.setString(1,x.runner.subject());ps.setObject(2,id);ps.setObject(3,id);try(var r=ps.executeQuery()){assertTrue(r.next());assertEquals(1,r.getInt(1));assertTrue(r.getBoolean(2));assertTrue(r.getBoolean(3));}}
        assertEquals(1,x.f.count("SELECT count(*) FROM di_ingest.event WHERE submission_id=? AND event_type='COMMITTED' AND initiator_id=(SELECT submitted_by FROM di_ingest.submission WHERE id=submission_id)",id));
    }}
    public static void main(String[] args)throws Exception{
        if(!"true".equals(System.getenv("DI_COMPOSE_TEST_FIXTURE")))throw new IllegalStateException("Isolated explicit fixture only");
        var tests=new IngestionG2IT();tests.officeGroupMembershipExpiryAndDelegation();System.out.println("PASS G22/G23 office, group, future scope, validity, inactive principal, least-privilege bootstrap/delegation");tests.submissionKeyAndSealBindExactInput();tests.idempotencyScopeAndExclusiveClaim();System.out.println("PASS G22/G25/G26 scope, idempotency, one claim, retry/cancel fencing");tests.atomicAcceptanceAndLostAcknowledgement();System.out.println("PASS G27/G29 atomic audited acceptance and lost-ACK receipt, no committed downgrade");tests.committedRevocationBlocksAcceptance();tests.disabledOriginalPrincipalCannotFinalize();tests.guardOrdersConcurrentRevocation();System.out.println("PASS G27 both concurrent guard orders and committed revocation");tests.flushedAcceptanceRollsBackAndDoesNotLeakActor();System.out.println("PASS G28 rollback after flush, one physical audited transaction, clean pool");
    }
}
