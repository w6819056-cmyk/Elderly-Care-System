package com.smartelder.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @Test
    void preservesSafeIncomingTraceId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIdFilter.HEADER_NAME, "tr_client_123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(request.getAttribute(TraceIdFilter.ATTRIBUTE_NAME)).isEqualTo("tr_client_123");
        assertThat(response.getHeader(TraceIdFilter.HEADER_NAME)).isEqualTo("tr_client_123");
        verify(chain).doFilter(request, response);
    }

    @Test
    void replacesUnsafeIncomingTraceId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIdFilter.HEADER_NAME, "../../bad trace");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        String traceId = (String) request.getAttribute(TraceIdFilter.ATTRIBUTE_NAME);
        assertThat(traceId).startsWith("tr_");
        assertThat(traceId).matches("[A-Za-z0-9_-]{8,64}");
        assertThat(response.getHeader(TraceIdFilter.HEADER_NAME)).isEqualTo(traceId);
    }
}