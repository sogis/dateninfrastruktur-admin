@import java.util.UUID
@import org.springframework.security.web.csrf.CsrfToken
@param UUID commandId
@param CsrfToken csrf
<input type="hidden" name="commandId" value="${commandId.toString()}">
<input type="hidden" name="${csrf.getParameterName()}" value="${csrf.getToken()}">
