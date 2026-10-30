package com.smartelder.platform.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartelder.platform.PlatformApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = PlatformApplication.class)
@AutoConfigureMockMvc
class PlatformHealthControllerTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void healthReturnsTraceableSuccessEnvelope() throws Exception {
        mvc.perform(get("/api/platform/v1/health")
                        .header("X-Trace-Id", "tr_platform_health"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Trace-Id", "tr_platform_health"))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.message").value("成功"))
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andExpect(jsonPath("$.traceId").value("tr_platform_health"))
                .andExpect(jsonPath("$.timestamp").isString());
    }
}