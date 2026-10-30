package com.smartelder.controller;

import com.smartelder.common.Result;
import com.smartelder.dto.HealthDtos;
import com.smartelder.security.CurrentUserHolder;
import com.smartelder.security.LoginUser;
import com.smartelder.service.HealthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "健康管理", description = "档案、体征、用药（HC-01~HC-10）")
@RestController
@RequestMapping("/api/health/elders/{elderId}")
public class HealthController {

    private final HealthService healthService;

    public HealthController(HealthService healthService) {
        this.healthService = healthService;
    }

    @Operation(summary = "查看电子健康档案（HC-01/HC-05）")
    @GetMapping("/archive")
    public Result<?> archive(@PathVariable Long elderId) {
        return Result.ok(healthService.getArchive(elderId, CurrentUserHolder.require()));
    }

    @Operation(summary = "维护健康档案")
    @PutMapping("/archive")
    public Result<?> saveArchive(@PathVariable Long elderId, @RequestBody HealthDtos.ArchiveRequest req) {
        return Result.ok(healthService.saveArchive(elderId, req, CurrentUserHolder.require()));
    }

    @Operation(summary = "体征上报（HC-02/HC-06）")
    @PostMapping("/vitals")
    public Result<HealthDtos.VitalView> reportVital(@PathVariable Long elderId,
                                                    @Valid @RequestBody HealthDtos.VitalRequest req,
                                                    @RequestParam(defaultValue = "MANUAL") String source) {
        return Result.ok(healthService.reportVital(elderId, req, CurrentUserHolder.require(), source));
    }

    @Operation(summary = "体征趋势（HC-06）")
    @GetMapping("/vitals")
    public Result<List<HealthDtos.VitalView>> vitals(@PathVariable Long elderId,
                                                     @RequestParam(required = false) String type,
                                                     @RequestParam(defaultValue = "30") int limit) {
        return Result.ok(healthService.listVitals(elderId, type, Math.min(limit, 200), CurrentUserHolder.require()));
    }

    @Operation(summary = "用药计划列表（HC-08）")
    @GetMapping("/medications")
    public Result<List<HealthDtos.MedicationView>> medications(@PathVariable Long elderId) {
        return Result.ok(healthService.listMedications(elderId, CurrentUserHolder.require()));
    }

    @Operation(summary = "新增用药计划（支持家属代设置）")
    @PostMapping("/medications")
    public Result<HealthDtos.MedicationView> addMedication(@PathVariable Long elderId,
                                                           @Valid @RequestBody HealthDtos.MedicationRequest req) {
        LoginUser user = CurrentUserHolder.require();
        return Result.ok(healthService.createMedication(elderId, req, user));
    }

    @Operation(summary = "停用用药计划")
    @DeleteMapping("/medications/{planId}")
    public Result<Void> stopMedication(@PathVariable Long elderId, @PathVariable Long planId) {
        healthService.stopMedication(planId, CurrentUserHolder.require());
        return Result.ok();
    }
}
