package com.smartelder.controller;

import com.smartelder.common.Result;
import com.smartelder.dto.HealthDtos;
import com.smartelder.entity.ElderProfile;
import com.smartelder.entity.FamilyBinding;
import com.smartelder.security.CurrentUserHolder;
import com.smartelder.service.FamilyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "家人互联", description = "状态面板、绑定、代下单（FM-01~FM-09）")
@RestController
@RequestMapping("/api/family")
public class FamilyController {

    private final FamilyService familyService;

    public FamilyController(FamilyService familyService) {
        this.familyService = familyService;
    }

    @Operation(summary = "家属已绑定的老人列表")
    @GetMapping("/elders")
    public Result<List<ElderProfile>> elders() {
        return Result.ok(familyService.myElders(CurrentUserHolder.require()));
    }

    @Operation(summary = "老人状态面板（FM-01/FM-05）")
    @GetMapping("/elders/{elderId}/snapshot")
    public Result<HealthDtos.ElderSnapshot> snapshot(@PathVariable Long elderId) {
        return Result.ok(familyService.snapshot(elderId, CurrentUserHolder.require()));
    }

    @Operation(summary = "家属发起绑定（USR-03），需老人确认")
    @PostMapping("/bindings")
    public Result<FamilyBinding> bind(@RequestBody Map<String, String> body) {
        Long elderId = Long.valueOf(body.get("elderId"));
        return Result.ok(familyService.bind(elderId, body.get("relation"), CurrentUserHolder.require()));
    }

    @Operation(summary = "老人查看待确认的绑定申请")
    @GetMapping("/bindings/pending")
    public Result<List<FamilyBinding>> pending() {
        return Result.ok(familyService.pendingOf(CurrentUserHolder.require()));
    }

    @Operation(summary = "老人确认/拒绝绑定（USR-02 双向确认）")
    @PostMapping("/bindings/{bindingId}/decide")
    public Result<Void> decide(@PathVariable Long bindingId, @RequestParam boolean accept) {
        familyService.decide(bindingId, accept, CurrentUserHolder.require());
        return Result.ok();
    }
}
