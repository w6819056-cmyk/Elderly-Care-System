package com.smartelder.platform;

import com.smartelder.platform.core.auth.PlatformAuthService;
import com.smartelder.platform.core.auth.PlatformPrincipal;
import com.smartelder.platform.core.common.PlatformApiException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class PlatformJwtFilter extends OncePerRequestFilter {

    public static final String AUTH_ERROR_ATTRIBUTE = "platform.auth.error";
    public static final String AUTH_ERROR_MESSAGE_ATTRIBUTE = "platform.auth.message";

    private final PlatformAuthService authService;

    public PlatformJwtFilter(PlatformAuthService authService) {
        this.authService = authService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization != null
                && authorization.startsWith("Bearer ")
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = authorization.substring("Bearer ".length()).trim();
            try {
                PlatformPrincipal principal = authService.resolveToken(token);
                Set<String> authorities = new LinkedHashSet<>();
                principal.roleCodes().forEach(role -> authorities.add("ROLE_" + role));
                authorities.addAll(principal.permissionCodes());
                var authentication = new UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        authorities.stream().map(SimpleGrantedAuthority::new).toList()
                );
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (PlatformApiException exception) {
                SecurityContextHolder.clearContext();
                request.setAttribute(AUTH_ERROR_ATTRIBUTE, exception.code().code());
                request.setAttribute(AUTH_ERROR_MESSAGE_ATTRIBUTE, exception.getMessage());
            }
        }
        filterChain.doFilter(request, response);
    }
}
