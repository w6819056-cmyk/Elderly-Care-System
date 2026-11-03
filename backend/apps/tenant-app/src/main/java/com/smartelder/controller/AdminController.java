package com.smartelder.controller;

import com.smartelder.common.Result;
import com.smartelder.dto.AdminDtos;
import com.smartelder.entity.SysUser;
import com.smartelder.security.CurrentUserHolder;
import com.smartelder.service.AdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "运营中台", description = "审核、用户管理、数据大屏（OP-01~OP-12）")
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    @Operation(summary = "服务商列表")
    @GetMapping("/providers")
    public Result<List<AdminDtos.ProviderRow>> providers(@RequestParam(required = false) String auditStatus) {
        return Result.ok(adminService.listProviders(auditStatus, CurrentUserHolder.require()));
    }

    @Operation(summary = "服务商入驻审核（OP-02/OP-08）")
    @PostMapping("/providers/{providerId}/audit")
    public Result<AdminDtos.ProviderRow> auditProvider(@PathVariable Long providerId,
                                                       @Valid @RequestBody AdminDtos.AuditRequest req) {
        return Result.ok(adminService.auditProvider(providerId, req, CurrentUserHolder.require()));
    }

    @Operation(summary = "服务项目列表")
    @GetMapping("/items")
    public Result<List<AdminDtos.PendingItemRow>> items(@RequestParam(required = false) String auditStatus) {
        return Result.ok(adminService.listItems(auditStatus, CurrentUserHolder.require()));
    }

    @Operation(summary = "服务项目审核（OP-03/OP-09）")
    @PostMapping("/items/{itemId}/audit")
    public Result<AdminDtos.PendingItemRow> auditItem(@PathVariable Long itemId,
                                                      @Valid @RequestBody AdminDtos.AuditRequest req) {
        return Result.ok(adminService.auditItem(itemId, req, CurrentUserHolder.require()));
    }

    @Operation(summary = "数据大屏指标（OP-06/OP-12）")
    @GetMapping("/dashboard")
    public Result<AdminDtos.DashboardStats> dashboard() {
        return Result.ok(adminService.dashboard(CurrentUserHolder.require()));
    }

    @Operation(summary = "用户管理查询（OP-01）")
    @GetMapping("/users")
    public Result<List<SysUser>> users(@RequestParam(required = false) String role,
                                       @RequestParam(required = false) String keyword) {
        return Result.ok(adminService.listUsers(role, keyword, CurrentUserHolder.require()));
    }

    @Operation(summary = "启用/停用账号（OP-01）")
    @PostMapping("/users/{userId}/status")
    public Result<Void> changeStatus(@PathVariable Long userId, @RequestBody Map<String, String> body) {
        adminService.changeUserStatus(userId, body.get("status"), CurrentUserHolder.require());
        return Result.ok();
    }
}
