package ch.so.agi.dateninfrastruktur.admin.legacy;
import java.nio.file.*;
import java.util.*;
import com.zaxxer.hikari.HikariDataSource;
import tools.jackson.databind.ObjectMapper;
import ch.so.agi.dateninfrastruktur.admin.audit.*;
import ch.so.agi.dateninfrastruktur.admin.persistence.*;
/** Local Java operator CLI. Dry-run is the default; apply requires explicit selection and technical identity. */
public final class LegacyCatalogImportCommand {
    public static void main(String[] args)throws Exception{
        if(args.length<1)throw new IllegalArgumentException("Usage: legacyImport <local.xtf> [--dry-run | --apply --select=id,...] [--report=local.json]");
        boolean apply=Arrays.asList(args).contains("--apply");String selection=Arrays.stream(args).filter(a->a.startsWith("--select=")).map(a->a.substring(9)).findFirst().orElse("");
        String report=Arrays.stream(args).filter(a->a.startsWith("--report=")).map(a->a.substring(9)).findFirst().orElse(".runtime/legacy-import-report.json");
        var source=LegacyCatalog.read(Path.of(args[0]));
        try(var ds=new HikariDataSource()){
            ds.setJdbcUrl(required("DI_MANAGEMENT_WRITER_JDBCURL"));ds.setUsername("di_writer");ds.setPassword(required("DI_MANAGEMENT_WRITER_PASSWORD"));ds.setMaximumPoolSize(1);
            RuntimeContractVerifier.require(ds);var runtime=ManagementRuntime.create(ds);
            try{var importer=new LegacyCatalogImportService(runtime);var plan=importer.dryRun(source);Path output=Path.of(report);if(output.toAbsolutePath().getParent()!=null)Files.createDirectories(output.toAbsolutePath().getParent());Files.writeString(output,new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(plan));output.toFile().setReadable(false,false);output.toFile().setReadable(true,true);output.toFile().setWritable(false,false);output.toFile().setWritable(true,true);
                if(apply){if(selection.isBlank())throw new IllegalArgumentException("Apply requires --select; no default whole-catalogue write");var actor=new ActorContext(required("DI_OPERATOR_ISSUER"),required("DI_OPERATOR_SUBJECT"),"service",null);var ids=importer.apply(actor,new CommandContext(UUID.randomUUID(),UUID.randomUUID(),"legacy.import","Explicit selected local legacy import"),source,Set.of(selection.split(",")));System.out.println("Applied/skipped "+ids.size()+" selected roots; report written. No legacy file is available for download.");}
                else System.out.println("Dry-run: "+plan.entries().size()+" roots, "+plan.entries().stream().filter(p->p.status().equals("CONFLICT")).count()+" conflicts. No catalogue or provenance write.");
            }finally{runtime.shutdown();}
        }
    }
    private static String required(String name){var value=System.getenv(name);if(value==null || value.isBlank())throw new IllegalArgumentException("Required operator configuration: "+name);return value;}
}
