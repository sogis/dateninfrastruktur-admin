package ch.so.agi.dateninfrastruktur.admin.ingestion;
import java.io.IOException;
import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import ch.so.agi.dateninfrastruktur.admin.audit.*;

final class IngestionHttp {
    private IngestionHttp(){}
    static ActorContext actor(JwtAuthenticationToken token,boolean service){if(token==null)throw IngestionProblem.forbidden();return new ActorContext(token.getToken().getIssuer().toString(),token.getToken().getSubject(),service?"service":"user",null);}
    static CommandContext command(HttpServletRequest request,String action){
        var keys=Collections.list(request.getHeaders("Idempotency-Key"));if(keys.size()!=1)throw IngestionProblem.invalid("INVALID_INPUT");
        try{return new CommandContext(UUID.fromString(keys.getFirst()),UUID.randomUUID(),action,"Interne Liefer-API");}catch(IllegalArgumentException e){throw IngestionProblem.invalid("INVALID_INPUT");}
    }
    static <T>T body(HttpServletRequest request,Class<T> type){
        if(request.getContentType()==null || !request.getContentType().split(";",2)[0].trim().equalsIgnoreCase("application/json"))throw new IngestionProblem(415,"INVALID_INPUT","JSON erforderlich.");
        try{
            byte[] bytes=request.getInputStream().readNBytes(65537);if(bytes.length>65536)throw new IngestionProblem(413,"INPUT_TOO_LARGE","API-Anfrage überschreitet die Grenze.");
            T result=CanonicalJson.JSON.readValue(bytes,type);if(result==null)throw IngestionProblem.invalid("INVALID_INPUT");return result;
        }catch(IOException e){throw new IngestionProblem(503,"DEPENDENCY_UNAVAILABLE","Anfrage konnte nicht vollständig gelesen werden.");}
        catch(tools.jackson.core.JacksonException | IllegalArgumentException e){throw IngestionProblem.invalid("INVALID_INPUT");}
    }
}
