package ch.so.agi.dateninfrastruktur.admin.operator;

import ch.so.agi.dateninfrastruktur.admin.audit.*;
import ch.so.agi.dateninfrastruktur.admin.identity.PrincipalRepository;
import ch.so.agi.dateninfrastruktur.admin.ingestion.CanonicalJson;
import ch.so.agi.dateninfrastruktur.admin.model.Principal;
import ch.so.agi.dateninfrastruktur.admin.persistence.*;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.*;
import java.util.UUID;

/** Explicit identity-only registration after obtaining the dedicated Keycloak service account. */
public final class DeliveryServiceIdentityCommand {
    public record Request(int schemaVersion,UUID commandId,String issuer,String subject){}
    public static UUID register(AuditedUnitOfWork unit,ActorContext operator,Request input,String expectedIssuer){
        if(!"service".equals(operator.kind()) || input.schemaVersion()!=1 || input.commandId()==null || !expectedIssuer.equals(input.issuer()) || input.subject()==null || !input.subject().matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))throw new IllegalArgumentException("Verified executor identity binding required");
        return unit.execute(operator,new CommandContext(input.commandId(),UUID.randomUUID(),"operator.register_delivery_service","Verified dedicated Keycloak service account"),context->{
            var p=new PrincipalRepository().findByExternalIdentity(context,input.issuer(),input.subject());
            if(p!=null){if(!p.isAactive() || !"service".equals(p.getAkind()))throw new IllegalStateException("Existing principal conflicts; no reactivation");return p.getTIliTid();}
            p=context.newObject(Principal.class);p.setTIliTid(UUID.randomUUID());p.setAissuer(input.issuer());p.setAsubject(input.subject());p.setAkind("service");p.setAactive(true);p.setProvisionedat(CayenneSql.now(context));return p.getTIliTid();
        });
    }
    public static void main(String[] args)throws Exception{
        if(args.length!=1)throw new IllegalArgumentException("Explicit private identity request required");
        var file=Path.of(args[0]);if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>8192||Files.getPosixFilePermissions(file).stream().anyMatch(p->p.name().startsWith("GROUP_")||p.name().startsWith("OTHERS_")))throw new IllegalArgumentException("Private regular identity request required");
        var request=CanonicalJson.JSON.readValue(Files.readAllBytes(file),Request.class);
        try(var ds=new HikariDataSource()){
            ds.setJdbcUrl(required("DI_MANAGEMENT_WRITER_JDBCURL"));ds.setUsername("di_writer");ds.setPassword(required("DI_MANAGEMENT_WRITER_PASSWORD"));ds.setMaximumPoolSize(1);RuntimeContractVerifier.require(ds);
            var runtime=ManagementRuntime.create(ds);try{
                register(new AuditedUnitOfWork(runtime),new ActorContext(required("DI_OPERATOR_ISSUER"),required("DI_OPERATOR_SUBJECT"),"service",null),request,required("DI_INGESTION_ISSUER"));
                System.out.println("Verified delivery service identity registered; no Fach grant created.");
            }finally{runtime.shutdown();}
        }
    }
    private static String required(String key){String value=System.getenv(key);if(value==null||value.isBlank())throw new IllegalArgumentException("Explicit operator environment required");return value;}
}
