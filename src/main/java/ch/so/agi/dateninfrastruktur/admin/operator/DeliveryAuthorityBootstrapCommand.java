package ch.so.agi.dateninfrastruktur.admin.operator;

import java.nio.file.*;
import java.util.*;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.cayenne.query.ObjectSelect;
import ch.so.agi.dateninfrastruktur.admin.audit.*;
import ch.so.agi.dateninfrastruktur.admin.model.*;
import ch.so.agi.dateninfrastruktur.admin.identity.PrincipalRepository;
import ch.so.agi.dateninfrastruktur.admin.persistence.*;
import ch.so.agi.dateninfrastruktur.admin.ingestion.*;

/** Explicit local first assignment. No MVC/Spring bean and no normal-start invocation. */
public final class DeliveryAuthorityBootstrapCommand {
    public record Request(int schemaVersion,UUID commandId,UUID officeId,UUID datasetId,String verifiedIssuer,String verifiedSubject,String reason){}
    public static UUID bootstrap(AuditedUnitOfWork unit,ActorContext operator,Request request){
        if(!"service".equals(operator.kind()) || request.schemaVersion()!=1 || request.commandId()==null || (request.officeId()==null)==(request.datasetId()==null) || request.verifiedIssuer()==null || request.verifiedIssuer().isBlank() || request.verifiedIssuer().length()>1024 || request.verifiedSubject()==null || request.verifiedSubject().isBlank() || request.verifiedSubject().length()>255 || request.reason()==null || request.reason().isBlank() || request.reason().length()>4000)throw IngestionProblem.invalid("INVALID_INPUT");
        var command=new CommandContext(request.commandId(),UUID.randomUUID(),"operator.bootstrap_submitters",request.reason());
        return unit.execute(operator,command,ctx->{
            var principals=new PrincipalRepository();var actor=principals.findByExternalIdentity(ctx,operator.issuer(),operator.subject());
            if(actor==null){actor=ctx.newObject(Principal.class);actor.setTIliTid(UUID.randomUUID());actor.setAissuer(operator.issuer());actor.setAsubject(operator.subject());actor.setAkind("service");actor.setAactive(true);actor.setProvisionedat(CayenneSql.now(ctx));}
            if(!actor.isAactive() || !"service".equals(actor.getAkind()))throw IngestionProblem.forbidden();
            var beneficiary=principals.findByExternalIdentity(ctx,request.verifiedIssuer(),request.verifiedSubject());
            if(beneficiary==null){beneficiary=ctx.newObject(Principal.class);beneficiary.setTIliTid(UUID.randomUUID());beneficiary.setAissuer(request.verifiedIssuer());beneficiary.setAsubject(request.verifiedSubject());beneficiary.setAkind("user");beneficiary.setAactive(true);beneficiary.setProvisionedat(CayenneSql.now(ctx));}
            if(!beneficiary.isAactive() || !"user".equals(beneficiary.getAkind()))throw IngestionProblem.forbidden();
            var receipts=new CommandReceiptRepository();var prior=receipts.findOrReserve(ctx,operator,command,CanonicalJson.digest(request));if(prior!=null)return prior.reference();
            Office office=null;Dataset dataset=null;
            if(request.officeId()!=null){office=ObjectSelect.query(Office.class).where(Office.T_ILI_TID.eq(request.officeId())).selectOne(ctx);if(office==null || !office.isAactive())throw IngestionProblem.forbidden();}
            else{dataset=ObjectSelect.query(Dataset.class).where(Dataset.T_ILI_TID.eq(request.datasetId())).selectOne(ctx);if(dataset==null || !dataset.getOwneroffice1().isAactive())throw IngestionProblem.forbidden();}
            var search=Authorityassignment.CAPABILITY.eq("manage_submitters").andExp(Authorityassignment.PRINCIPAL1.dot(Principal.T_ILI_TID).eq(beneficiary.getTIliTid())).andExp(request.officeId()!=null?Authorityassignment.OFFICE1.dot(Office.T_ILI_TID).eq(request.officeId()):Authorityassignment.DATASET1.dot(Dataset.T_ILI_TID).eq(request.datasetId()));
            var existing=ObjectSelect.query(Authorityassignment.class).where(search).select(ctx);
            UUID id;
            if(!existing.isEmpty()){
                if(existing.size()!=1 || existing.getFirst().getRevokedat()!=null || (existing.getFirst().getValiduntil()!=null && !CayenneSql.now(ctx).isBefore(existing.getFirst().getValiduntil())))throw IngestionProblem.conflict("INVALID_STATE");id=existing.getFirst().getTIliTid();
            }else{
                var assignment=ctx.newObject(Authorityassignment.class);id=UUID.randomUUID();assignment.setTIliTid(id);assignment.setCapability("manage_submitters");assignment.setPrincipal1(beneficiary);assignment.setAssignedby1(actor);assignment.setOffice1(office);assignment.setDataset1(dataset);assignment.setValidfrom(CayenneSql.now(ctx));assignment.setReason(request.reason());
            }
            receipts.complete(ctx,operator,command,new CommandReceiptRepository.Receipt(id,"COMMITTED"));return id;
        });
    }
    public static void main(String[] args)throws Exception{
        if(args.length!=1)throw new IllegalArgumentException("Explicit private bootstrap request file required");
        Path path=Path.of(args[0]);var permissions=Files.getPosixFilePermissions(path);if(permissions.stream().anyMatch(p->p.name().startsWith("GROUP_")||p.name().startsWith("OTHERS_")) || !Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || Files.size(path)>16384)throw new IllegalArgumentException("Private bounded regular request required");
        var request=CanonicalJson.JSON.readValue(Files.readAllBytes(path),Request.class);var actor=new ActorContext(required("DI_OPERATOR_ISSUER"),required("DI_OPERATOR_SUBJECT"),"service",null);
        try(var ds=new HikariDataSource()){
            ds.setJdbcUrl(required("DI_MANAGEMENT_WRITER_JDBCURL"));ds.setUsername("di_writer");ds.setPassword(required("DI_MANAGEMENT_WRITER_PASSWORD"));ds.setMaximumPoolSize(1);RuntimeContractVerifier.require(ds);
            var runtime=ManagementRuntime.create(ds);try{System.out.println("Configured manage_submitters assignment "+bootstrap(new AuditedUnitOfWork(runtime),actor,request)+"; no other authority or data grant created.");}finally{runtime.shutdown();}
        }
    }
    private static String required(String key){String value=System.getenv(key);if(value==null || value.isBlank())throw new IllegalArgumentException("Explicit operator environment required");return value;}
}
