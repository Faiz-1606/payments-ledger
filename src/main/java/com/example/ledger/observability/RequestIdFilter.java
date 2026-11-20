package com.example.ledger.observability;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every HTTP request an id and puts it in every log line written while handling it.
 *
 * <p>The id comes from the {@code X-Request-Id} header if the caller (e.g. a load balancer or
 * gateway) sent a well-formed one, otherwise a new UUID. It is
 * <ul>
 *   <li>stored in the SLF4J <b>MDC</b> (Mapped Diagnostic Context, a per-thread map that the
 *       log pattern can print: see {@code logging.pattern.correlation} in application.yml),</li>
 *   <li>echoed in the {@code X-Request-Id} response header,</li>
 *   <li>added to every error body as {@code requestId} (GlobalExceptionHandler),</li>
 * </ul>
 * so "my transfer failed, here is the requestId" leads straight to the server's log lines.
 *
 * <p>{@code HIGHEST_PRECEDENCE} runs this before Spring Security, so even authentication
 * failures are tagged.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    /** Accept only short, log-safe ids; anything else (e.g. with newlines) is replaced. */
    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Logger accessLog = LoggerFactory.getLogger("com.example.ledger.access");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = request.getHeader(HEADER);
        if (requestId == null || !VALID_ID.matcher(requestId).matches()) {
            requestId = UUID.randomUUID().toString();
        }
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        long startNanos = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long millis = (System.nanoTime() - startNanos) / 1_000_000;
            accessLog.info("{} {} -> {} in {} ms", request.getMethod(), request.getRequestURI(),
                    response.getStatus(), millis);
            // Threads are pooled: without this the next request on this thread would inherit the id.
            MDC.remove(MDC_KEY);
        }
    }

    /** The current request's id, or null outside a request. */
    public static String currentRequestId() {
        return MDC.get(MDC_KEY);
    }
}
