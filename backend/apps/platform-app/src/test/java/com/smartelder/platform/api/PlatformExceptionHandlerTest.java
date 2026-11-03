package com.smartelder.platform.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartelder.common.ErrorCode;
import com.smartelder.common.TraceIdFilter;
import com.smartelder.platform.core.common.PlatformApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

class PlatformExceptionHandlerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ErrorProbeController())
                .setControllerAdvice(new PlatformExceptionHandler(Clock.fixed(
                        Instant.parse("2026-10-30T12:00:00Z"), ZoneOffset.UTC
                )))
                .addFilters(new TraceIdFilter())
                .build();
    }

    @Test
    void malformedJsonReturnsTraceableBadRequestEnvelope() throws Exception {
        mvc.perform(post("/probe/body")
                        .header(TraceIdFilter.HEADER_NAME, "tr_bad_json_01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(TraceIdFilter.HEADER_NAME, "tr_bad_json_01"))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.traceId").value("tr_bad_json_01"));
    }

    @Test
    void accessDeniedReturnsPermissionDeniedEnvelope() throws Exception {
        mvc.perform(post("/probe/forbidden")
                        .header(TraceIdFilter.HEADER_NAME, "tr_forbid_01"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"))
                .andExpect(jsonPath("$.traceId").value("tr_forbid_01"));
    }

    @Test
    void stateConflictPreservesStableErrorCode() throws Exception {
        mvc.perform(post("/probe/conflict")
                        .header(TraceIdFilter.HEADER_NAME, "tr_conflict_01"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_STATE_CONFLICT"));
    }

    @Test
    void dependencyFailurePreservesUnprocessableStatus() throws Exception {
        mvc.perform(post("/probe/dependency")
                        .header(TraceIdFilter.HEADER_NAME, "tr_depend_01"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MODULE_DEPENDENCY_MISSING"));
    }

    @Test
    void unknownFailureReturnsSanitizedInternalErrorEnvelope() throws Exception {
        mvc.perform(post("/probe/unknown")
                        .header(TraceIdFilter.HEADER_NAME, "tr_unknown_01"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value(ErrorCode.INTERNAL_ERROR.defaultMessage()))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("sensitive database detail")
                )))
                .andExpect(jsonPath("$.traceId").value("tr_unknown_01"));
    }

    @RestController
    static class ErrorProbeController {

        @PostMapping("/probe/body")
        String body(@RequestBody ProbeBody body) {
            return body.value();
        }

        @PostMapping("/probe/forbidden")
        void forbidden() {
            throw new org.springframework.security.access.AccessDeniedException("denied");
        }

        @PostMapping("/probe/conflict")
        void conflict() {
            throw new PlatformApiException(ErrorCode.ORDER_STATE_CONFLICT, null);
        }

        @PostMapping("/probe/dependency")
        void dependency() {
            throw new PlatformApiException(ErrorCode.MODULE_DEPENDENCY_MISSING, null);
        }

        @PostMapping("/probe/unknown")
        void unknown() {
            throw new IllegalStateException("sensitive database detail");
        }
    }

    record ProbeBody(String value) {
    }
}
