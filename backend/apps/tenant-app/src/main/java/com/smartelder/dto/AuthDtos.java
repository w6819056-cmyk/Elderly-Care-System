package com.smartelder.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
            @NotBlank @Pattern(regexp = "1[3-9]\\d{9}", message = "手机号格式不正确") String phone,
            @NotBlank @Size(min = 6, max = 32, message = "密码长度 6-32 位") String password,
            @NotBlank String nickname,
            /** ELDER / FAMILY */
            @NotBlank String role) {
    }

    public record LoginRequest(
            @NotBlank String phone,
            @NotBlank String password) {
    }

    public record UserInfo(Long userId, String phone, String nickname, String role, Long orgId, Long elderId) {
    }

    public record TokenResponse(String token, UserInfo user) {
    }
}
