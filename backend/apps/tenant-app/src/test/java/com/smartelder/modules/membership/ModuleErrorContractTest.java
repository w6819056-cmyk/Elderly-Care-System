package com.smartelder.modules.membership;
import com.smartelder.api.tenant.TenantApiExceptionHandler;
import com.smartelder.common.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ModuleErrorContractTest {
 @RestController static class Fixture {@GetMapping("/fixture") public Object rejected(){throw new ResponseStatusException(HttpStatus.FORBIDDEN,"仅总部可管理会员");}}
 @Test void externalModuleErrorsPreserveHttpStatusAndApiEnvelope()throws Exception{
  var mvc=MockMvcBuilders.standaloneSetup(new Fixture()).setControllerAdvice(new GlobalExceptionHandler(),new TenantApiExceptionHandler()).build();
  mvc.perform(get("/fixture")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED")).andExpect(jsonPath("$.message").value("仅总部可管理会员"));
 }
}
