package com.smartelder.platform.api.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartelder.platform.PlatformApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@ActiveProfiles("test")
@SpringBootTest(classes = PlatformApplication.class)
@AutoConfigureMockMvc
class PlatformAuthControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void loginReturnsTokenAndMeLoadsPrincipal() throws Exception {
        String response = mvc.perform(post("/api/platform/v1/auth/login")
                        .header("X-Trace-Id", "tr_platform_login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"admin","password":"ChangeMe-Platform-2026"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.expiresAt").isString())
                .andExpect(jsonPath("$.data.principal.username").value("admin"))
                .andExpect(jsonPath("$.data.principal.permissionCodes").isArray())
                .andExpect(jsonPath("$.data.principal.permissionCodes[?(@ == 'platform.enterprise.manage')]").exists())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode body = objectMapper.readTree(response);
        String token = body.path("data").path("accessToken").asText();

        mvc.perform(get("/api/platform/v1/auth/me")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Trace-Id", "tr_platform_me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.username").value("admin"))
                .andExpect(jsonPath("$.data.roleCodes[0]").value("PLATFORM_SUPER_ADMIN"))
                .andExpect(jsonPath("$.data.permissionCodes").isArray());
    }

    @Test
    void loginRejectsWrongPasswordWithUnifiedEnvelope() throws Exception {
        mvc.perform(post("/api/platform/v1/auth/login")
                        .header("X-Trace-Id", "tr_platform_bad_login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"admin","password":"wrong-password"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.traceId").value("tr_platform_bad_login"));
    }

    @Test
    void protectedEndpointRequiresPlatformToken() throws Exception {
        mvc.perform(get("/api/platform/v1/auth/me")
                        .header("X-Trace-Id", "tr_platform_no_token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.traceId").value("tr_platform_no_token"));
    }

    @Test
    void malformedTokenReturnsTokenInvalid() throws Exception {
        mvc.perform(get("/api/platform/v1/auth/me")
                        .header("Authorization", "Bearer not-a-jwt")
                        .header("X-Trace-Id", "tr_platform_bad_token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"))
                .andExpect(jsonPath("$.traceId").value("tr_platform_bad_token"));
    }
}
