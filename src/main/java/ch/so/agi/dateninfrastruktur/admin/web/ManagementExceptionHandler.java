package ch.so.agi.dateninfrastruktur.admin.web;

import java.time.DateTimeException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import ch.so.agi.dateninfrastruktur.admin.access.BusinessException;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.RequestPath;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

@ControllerAdvice
public final class ManagementExceptionHandler {
    private static final PathPattern MANAGEMENT = PathPatternParser.defaultInstance.parse("/management/**");
    private static final PathPattern ACCESS = PathPatternParser.defaultInstance.parse("/access/**");

    public record Error(String code, String message) {}

    private record Failure(int status, String code, String message) {}

    @ExceptionHandler(Exception.class)
    Object error(Exception exception, HttpServletRequest request, HttpServletResponse response) throws Exception {
        var path = RequestPath.parse(request.getRequestURI(), request.getContextPath()).pathWithinApplication();
        if (MANAGEMENT.matches(path) || ACCESS.matches(path)) {
            if (exception instanceof AuthenticationException) {
                throw exception;
            }
            return browserError(exception, response);
        }
        var failure = failure(exception);
        return ResponseEntity.status(failure.status()).body(new Error(failure.code(), failure.message()));
    }

    private static ModelAndView browserError(Exception exception, HttpServletResponse response) {
        if (exception instanceof AccessDeniedException) {
            return browserView(403, "Für diesen Vorgang sind Sie nicht zuständig.");
        }
        if (exception instanceof ErrorResponse webError) {
            webError.getHeaders().forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
            int status = webError.getStatusCode().value();
            String message = switch (status) {
                case 400 -> "Die Angaben sind unvollständig oder ungültig.";
                case 403 -> "Für diesen Vorgang sind Sie nicht zuständig.";
                case 404 -> "Der Eintrag wurde nicht gefunden.";
                case 405 -> "Diese HTTP-Methode ist nicht erlaubt.";
                default -> "Die Anfrage konnte nicht verarbeitet werden.";
            };
            return browserView(status, message);
        }
        var failure = failure(exception);
        return browserView(failure.status(), failure.message());
    }

    private static ModelAndView browserView(int status, String message) {
        String title = switch (status) {
            case 400 -> "Angaben prüfen";
            case 403 -> "Zugriff verweigert";
            case 404 -> "Nicht gefunden";
            case 409 -> "Änderung nicht gespeichert";
            default -> "Anfrage fehlgeschlagen";
        };
        var view = new ModelAndView("browser-error", HttpStatusCode.valueOf(status));
        view.addObject("title", title);
        view.addObject("message", message);
        return view;
    }

    private static Failure failure(Exception error) {
        Throwable cause = error;
        for (int i = 0; i < 10 && cause != null; i++, cause = cause.getCause()) {

            if (cause instanceof BusinessException business) {
                return new Failure(business.status(), business.code(), business.getMessage());
            }
        }
        if (error instanceof MethodArgumentTypeMismatchException || error instanceof MissingServletRequestParameterException
                || error instanceof IllegalArgumentException || error instanceof DateTimeException) {
            return new Failure(400, "INVALID_INPUT", "Die Angaben sind unvollständig oder ungültig.");
        }
        if (error instanceof HttpRequestMethodNotSupportedException) {
            return new Failure(405, "METHOD_NOT_ALLOWED", "Diese HTTP-Methode ist nicht erlaubt.");
        }
        return new Failure(503, "TEMPORARILY_UNAVAILABLE",
            "Der Vorgang ist vorübergehend nicht verfügbar. Bitte neu laden; derselbe Vorgangsschlüssel kann erneut gesendet werden.");
    }
}
