package com.smartelder.platform.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartelder.common.ApiResponse;
import com.smartelder.common.ErrorCode;
import com.smartelder.common.TraceIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import org.springframework.http.MediaType;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class CachedBodyRequestFilter extends OncePerRequestFilter {

    private final ObjectMapper objectMapper;
    private final Clock clock;

    static final int MAX_BODY_BYTES = 64 * 1024;

    public static final String CACHED_BODY_ATTRIBUTE =
            CachedBodyRequestFilter.class.getName() + ".CACHED_BODY";

    public CachedBodyRequestFilter(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            writePayloadTooLarge(request, response);
            return;
        }
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            writePayloadTooLarge(request, response);
            return;
        }
        request.setAttribute(CACHED_BODY_ATTRIBUTE, body);
        filterChain.doFilter(new CachedBodyHttpServletRequest(request, body), response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/platform/v1/instances/");
    }

    private void writePayloadTooLarge(
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        String traceId = (String) request.getAttribute(TraceIdFilter.ATTRIBUTE_NAME);
        response.setStatus(ErrorCode.PAYLOAD_TOO_LARGE.httpStatus());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(
                response.getWriter(),
                ApiResponse.error(ErrorCode.PAYLOAD_TOO_LARGE, null, traceId, clock)
        );
    }
}
