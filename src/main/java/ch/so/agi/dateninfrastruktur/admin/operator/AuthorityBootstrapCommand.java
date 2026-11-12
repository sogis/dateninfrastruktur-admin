package ch.so.agi.dateninfrastruktur.admin.operator;
import java.util.*;
import org.apache.cayenne.query.ObjectSelect;
import org.apache.cayenne.ObjectContext;
import com.zaxxer.hikari.HikariDataSource;
import ch.so.agi.dateninfrastruktur.admin.audit.*;
import ch.so.agi.dateninfrastruktur.admin.model.*;
import ch.so.agi.dateninfrastruktur.admin.persistence.*;
/** Local operator entry point, deliberately absent from Spring/MVC. No secret or token CLI arguments. */
public final class AuthorityBootstrapCommand {
    public static void main(String[] args)throws Exception{
        if(args.length!=5 || !args[0].equals("bootstrap-office"))throw new IllegalArgumentException("Usage: operator bootstrap-office <verified-issuer> <verified-subject> <office-identifier> <office-name>");
        var actor=new ActorContext(required("DI_OPERATOR_ISSUER"),required("DI_OPERATOR_SUBJECT"),"service",null);
        try(var ds=new HikariDataSource()){
            ds.setJdbcUrl(required("DI_MANAGEMENT_WRITER_JDBCURL"));ds.setUsername("di_writer");ds.setPassword(required("DI_MANAGEMENT_WRITER_PASSWORD"));ds.setMaximumPoolSize(1);
            var runtime=ManagementRuntime.create(ds);
            try{
                var office=new AuditedUnitOfWork(runtime).execute(actor,new CommandContext(UUID.randomUUID(),UUID.randomUUID(),"operator.bootstrap_office","Explicit local operator setup"),ctx->{
                    var operator=principal(ctx,actor.issuer(),actor.subject(),"service");var user=principal(ctx,args[1],args[2],"user");
                    var row=ObjectSelect.query(Office.class).where(Office.IDENTIFIER.eq(args[3])).selectOne(ctx);
                    if(row==null){row=ctx.newObject(Office.class);row.setTIliTid(args[3]);row.setIdentifier(args[3]);row.setAname(args[4]);row.setAbbreviation(args[3].substring(0,Math.min(32,args[3].length())));row.setAactive(true);}
                    // Resolve existing assignments by stable OIDs, including a safe first bootstrap before the ORM flush.
                    for(String capability:List.of("manage_metadata","publish","manage_access")){
                        var found=ObjectSelect.query(Authorityassignment.class).where(Authorityassignment.OFFICE1.dot(Office.T_ILI_TID).eq(row.getTIliTid()).andExp(Authorityassignment.PRINCIPAL1.dot(Principal.T_ILI_TID).eq(user.getTIliTid())).andExp(Authorityassignment.CAPABILITY.eq(capability))).select(ctx);
                        if(found.isEmpty()){var a=ctx.newObject(Authorityassignment.class);a.setTIliTid(UUID.randomUUID());a.setOffice1(row);a.setPrincipal1(user);a.setAssignedby1(operator);a.setCapability(capability);a.setValidfrom(CayenneSql.now(ctx));a.setReason("Explicit operator bootstrap");}
                    }
                    return row.getTIliTid();
                });System.out.println("Configured office "+office+"; no data grant was created.");
            }finally{runtime.shutdown();}
        }
    }
    private static Principal principal(ObjectContext ctx,String issuer,String subject,String kind){
        var found=ObjectSelect.query(Principal.class).where(Principal.AISSUER.eq(issuer).andExp(Principal.ASUBJECT.eq(subject))).selectOne(ctx);
        if(found!=null){if(!kind.equals(found.getAkind()) || !found.isAactive())throw new IllegalStateException("Existing principal conflicts; no implicit reactivation");return found;}
        var row=ctx.newObject(Principal.class);row.setTIliTid(UUID.randomUUID());row.setAissuer(issuer);row.setAsubject(subject);row.setAkind(kind);row.setAactive(true);row.setProvisionedat(CayenneSql.now(ctx));return row;
    }
    private static String required(String key){var value=System.getenv(key);if(value==null || value.isBlank())throw new IllegalArgumentException("Required operator configuration: "+key);return value;}
}
