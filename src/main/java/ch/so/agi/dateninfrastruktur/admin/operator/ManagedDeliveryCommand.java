package ch.so.agi.dateninfrastruktur.admin.operator;
import java.nio.file.Path;
import java.util.UUID;
import com.zaxxer.hikari.HikariDataSource;
import ch.so.agi.dateninfrastruktur.admin.audit.*;
import ch.so.agi.dateninfrastruktur.admin.access.AuthorityService;
import ch.so.agi.dateninfrastruktur.admin.catalog.*;
import ch.so.agi.dateninfrastruktur.admin.persistence.*;
/** Explicit scoped operator activation. No HTTP route, no upload and no storage credentials in web apps. */
public final class ManagedDeliveryCommand {
    public static void main(String[] args)throws Exception{
        if(args.length!=4 || !args[0].equals("activate"))throw new IllegalArgumentException("Usage: activate <dataset-uuid> <expected-version> <distribution-uuid>");
        try(var ds=new HikariDataSource()){
            ds.setJdbcUrl(required("DI_MANAGEMENT_WRITER_JDBCURL"));ds.setUsername("di_writer");ds.setPassword(required("DI_MANAGEMENT_WRITER_PASSWORD"));ds.setMaximumPoolSize(1);
            RuntimeContractVerifier.require(ds);var runtime=ManagementRuntime.create(ds);
            try{
                var verifier=new RclonePrivateObjectVerifier(Path.of(required("DI_RCLONE_EXECUTABLE")),Path.of(required("DI_RCLONE_CONFIG")));
                var service=new DeliveryRegistrationService(new AuditedUnitOfWork(runtime),new AuthorityService(runtime),verifier);
                service.verifyAndActivate(new ActorContext(required("DI_OPERATOR_ISSUER"),required("DI_OPERATOR_SUBJECT"),"service",null),new CommandContext(UUID.randomUUID(),UUID.randomUUID(),"activate_distribution","Explicit verified private delivery"),UUID.fromString(args[1]),Long.parseLong(args[2]),UUID.fromString(args[3]));
                System.out.println("Private object verified and distribution activated.");
            }finally{runtime.shutdown();}
        }
    }
    private static String required(String key){var value=System.getenv(key);if(value==null || value.isBlank())throw new IllegalArgumentException("Required operator configuration: "+key);return value;}
}
