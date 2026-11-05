package ch.so.agi.dateninfrastruktur.admin.ingestion;
import java.util.Map;
import org.apache.cayenne.ObjectContext;
import ch.so.agi.dateninfrastruktur.admin.audit.ActorContext;
import ch.so.agi.dateninfrastruktur.admin.persistence.CayenneSql;
/** HTTP additionally verifies the dedicated client, audience, access-token type and role. */
public final class IngestionRunnerAuthorization {
    public long require(ObjectContext context,ActorContext actor){
        if(!"service".equals(actor.kind()))throw IngestionProblem.forbidden();
        var rows=CayenneSql.rows(context,"SELECT t_id FROM di_management.principal WHERE aissuer=#bind($issuer) AND asubject=#bind($subject) AND aactive AND akind='service'",Map.of("issuer",actor.issuer(),"subject",actor.subject()));
        if(rows.size()!=1)throw IngestionProblem.forbidden();return ((Number)rows.getFirst().get("t_id")).longValue();
    }
}
