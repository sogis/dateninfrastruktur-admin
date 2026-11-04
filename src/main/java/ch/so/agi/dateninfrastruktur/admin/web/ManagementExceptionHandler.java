package ch.so.agi.dateninfrastruktur.admin.web;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import ch.so.agi.dateninfrastruktur.admin.access.BusinessException;
import ch.so.agi.dateninfrastruktur.admin.persistence.AggregateWriteGuard;
@RestControllerAdvice
public final class ManagementExceptionHandler {
    public record Error(String code,String message){}
    @ExceptionHandler(Exception.class) ResponseEntity<Error> error(Exception error){
        Throwable cause=error;
        for(int i=0;i<10 && cause!=null;i++,cause=cause.getCause()){
            if(cause instanceof BusinessException business)return ResponseEntity.status(business.status()).body(new Error(business.code(),business.getMessage()));
            if(cause instanceof AggregateWriteGuard.StaleAggregateException)return ResponseEntity.status(409).body(new Error("STALE_VERSION","Der Eintrag wurde inzwischen verändert. Bitte neu laden."));
            if(cause instanceof AggregateWriteGuard.MissingAggregateException)return ResponseEntity.status(404).body(new Error("NOT_FOUND","Der Eintrag wurde nicht gefunden."));
        }
        if(error instanceof MethodArgumentTypeMismatchException || error instanceof org.springframework.web.bind.MissingServletRequestParameterException || error instanceof IllegalArgumentException)
            return ResponseEntity.badRequest().body(new Error("INVALID_INPUT","Die Angaben sind unvollständig oder ungültig."));
        if(error instanceof org.springframework.web.HttpRequestMethodNotSupportedException)return ResponseEntity.status(405).body(new Error("METHOD_NOT_ALLOWED","Diese HTTP-Methode ist nicht erlaubt."));
        return ResponseEntity.status(503).body(new Error("TEMPORARILY_UNAVAILABLE","Der Vorgang ist vorübergehend nicht verfügbar. Bitte neu laden; derselbe Vorgangsschlüssel kann erneut gesendet werden."));
    }
}
