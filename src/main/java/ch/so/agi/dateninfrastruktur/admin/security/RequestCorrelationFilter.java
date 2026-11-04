package ch.so.agi.dateninfrastruktur.admin.security;
import java.io.IOException;
import java.util.UUID;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
public final class RequestCorrelationFilter extends OncePerRequestFilter {
    public static final String ATTRIBUTE="di.requestId";
    protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException{
        String raw=request.getHeader("X-Request-ID");UUID id;
        try{id=UUID.fromString(raw);if(!id.toString().equals(raw))throw new IllegalArgumentException();}
        catch(IllegalArgumentException|NullPointerException invalid){id=UUID.randomUUID();}
        request.setAttribute(ATTRIBUTE,id);response.setHeader("X-Request-ID",id.toString());chain.doFilter(request,response);
    }
}
