package com.example.ledger.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Security failures happen in filters, <em>before</em> any controller runs, so
 * {@code @RestControllerAdvice} would never see them. We hand them to Spring MVC's
 * {@link HandlerExceptionResolver}, which routes them to {@code GlobalExceptionHandler}. That
 * way 401 and 403 responses are RFC 7807 problem details, just like every other error.
 */
@Component
public class ProblemDetailsSecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final HandlerExceptionResolver resolver;

    public ProblemDetailsSecurityHandlers(@Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        this.resolver = resolver;
    }

    /** No or invalid credentials: 401, and tell the client which scheme to use. */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        resolver.resolveException(request, response, null, authException);
    }

    /** Valid credentials, insufficient role: 403. */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        resolver.resolveException(request, response, null, accessDeniedException);
    }
}
