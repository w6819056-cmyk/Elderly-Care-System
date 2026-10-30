package com.smartelder.controller;

import com.smartelder.common.Result;
import com.smartelder.dto.AlertDtos;
import com.smartelder.entity.AlertHandleLog;
import com.smartelder.security.CurrentUserHolder;
import com.smartelder.service.AlertService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "安全监护", description = "SOS、跌倒、围栏、告警处置（SA-01~SA-10）")
@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    private final AlertService alertService;

    public AlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    @Operation(summary = "老人一键 SOS（SA-01/SA-04）")
    @PostMapping("/sos")
    public Result<AlertDtos.AlertView> sos(@RequestBody(required = false) AlertDtos.SosRequest req) {
        return Result.ok(alertService.sos(req == null ? new AlertDtos.SosRequest(null, null, null) : req,
                CurrentUserHolder.require()));
    }

    @Operation(summary = "设备/模拟事件触发告警（SA-05~SA-07）")
    @PostMapping("/elders/{elderId}/trigger")
    public Result<AlertDtos.AlertView> trigger(@PathVariable Long elderId,
                                                @Valid @RequestBody AlertDtos.TriggerRequest req) {
        return Result.ok(alertService.deviceTrigger(elderId, req, CurrentUserHolder.require()));
    }

    @Operation(summary = "某老人的告警列表（SA-02）")
    @GetMapping("/elders/{elderId}")
    public Result<List<AlertDtos.AlertView>> listOfElder(@PathVariable Long elderId,
                                                          @RequestParam(required = false) String status,
                                                          @RequestParam(defaultValue = "30") int limit) {
        return Result.ok(alertService.listForElder(elderId, status, Math.min(limit, 200),
                CurrentUserHolder.require()));
    }

    @Operation(summary = "全平台告警工作台")
    @GetMapping
    public Result<List<AlertDtos.AlertView>> listAll(@RequestParam(required = false) String status,
                                                     @RequestParam(required = false) String level,
                                                     @RequestParam(defaultValue = "50") int limit) {
        return Result.ok(alertService.listAll(status, level, Math.min(limit, 200), CurrentUserHolder.require()));
    }

    @Operation(summary = "告警处置（SA-09）：action=ACK/FOLLOW/ARCHIVE")
    @PostMapping("/{alertId}/handle")
    public Result<AlertDtos.AlertView> handle(@PathVariable Long alertId,
                                              @Valid @RequestBody AlertDtos.HandleRequest req) {
        return Result.ok(alertService.handle(alertId, req, CurrentUserHolder.require()));
    }

    @Operation(summary = "告警处置流水")
    @GetMapping("/{alertId}/logs")
    public Result<List<AlertHandleLog>> logs(@PathVariable Long alertId) {
        return Result.ok(alertService.handleLogsFor(alertId, CurrentUserHolder.require()));
    }
}
