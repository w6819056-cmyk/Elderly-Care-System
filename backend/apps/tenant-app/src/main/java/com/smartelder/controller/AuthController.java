package com.smartelder.controller;

import com.smartelder.common.Result;
import com.smartelder.dto.AuthDtos;
import com.smartelder.security.CurrentUserHolder;
import com.smartelder.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "认证", description = "注册、登录与当前用户")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @Operation(summary = "注册老人或家属账号（USR-01）")
    @PostMapping("/register")
    public Result<AuthDtos.TokenResponse> register(@Valid @RequestBody AuthDtos.RegisterRequest req) {
        return Result.ok(authService.register(req));
    }

    @Operation(summary = "登录")
    @PostMapping("/login")
    public Result<AuthDtos.TokenResponse> login(@Valid @RequestBody AuthDtos.LoginRequest req) {
        return Result.ok(authService.login(req));
    }

    @Operation(summary = "当前登录用户信息")
    @GetMapping("/me")
    public Result<AuthDtos.UserInfo> me() {
        return Result.ok(authService.me(CurrentUserHolder.require().userId()));
    }
}
