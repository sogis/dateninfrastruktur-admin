package ch.so.agi.dateninfrastruktur.admin.ingestion;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import ch.so.agi.dateninfrastruktur.admin.access.BusinessException;

@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes={IngestionUserController.class,IngestionExecutionController.class})
public final class IngestionProblemAdvice {
    public record Problem(int status,String code,String detail){}
    @ExceptionHandler(IngestionProblem.class) ResponseEntity<Problem> ingestion(IngestionProblem e){return problem(e.status(),e.code(),e.getMessage());}
    @ExceptionHandler(BusinessException.class) ResponseEntity<Problem> business(BusinessException e){return problem(e.status(),e.status()==403?"NOT_AUTHORIZED":"INVALID_STATE","Vorgang durch aktuelle Fachprüfung verweigert.");}
    @ExceptionHandler({org.apache.cayenne.CayenneRuntimeException.class,org.springframework.dao.DataAccessException.class}) ResponseEntity<Problem> dependency(Exception e){return problem(503,"DEPENDENCY_UNAVAILABLE","Benötigter Dienst ist nicht verfügbar.");}
    @ExceptionHandler({IllegalArgumentException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class}) ResponseEntity<Problem> invalid(Exception e){return problem(400,"INVALID_INPUT","Die Angaben sind ungültig.");}
    private static ResponseEntity<Problem> problem(int status,String code,String detail){return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(new Problem(status,code,detail));}
}
