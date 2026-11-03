package com.smartelder.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartelder.common.ApiResponse;
import com.smartelder.common.ErrorCode;
import com.smartelder.common.TraceIdFilter;
import java.io.IOException;
import java.time.Clock;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class PlatformSecurityConfiguration {

    private final PlatformJwtFilter jwtFilter;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public PlatformSecurityConfiguration(
            PlatformJwtFilter jwtFilter,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.jwtFilter = jwtFilter;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Bean
    SecurityFilterChain platformSecurityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(
                                "/api/platform/v1/health",
                                "/api/platform/v1/auth/login",
                                "/api/platform/v1/instances/activate"
                        ).permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/platform/v1/enterprise-applications").permitAll()
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/platform/v1/instances/heartbeat"
                        ).permitAll()
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/platform/v1/instances/*/license"
                                ,"/api/platform/v1/instances/*/module-packages", "/api/platform/v1/instances/*/module-packages/*/*"
                        ).permitAll()
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/platform/v1/instances/*/ack-license"
                        ).permitAll()
                        .requestMatchers(
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/v3/api-docs/**"
                        ).permitAll()
                        .requestMatchers(HttpMethod.GET,"/api/platform/v1/instances/*/commerce/**").permitAll()
                        .requestMatchers(HttpMethod.POST,"/api/platform/v1/instances/*/commerce/**").permitAll()
                        .requestMatchers("/api/platform/v1/**").authenticated()
                        .anyRequest().denyAll()
                )
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> {
                            String requestedCode = (String) request.getAttribute(
                                    PlatformJwtFilter.AUTH_ERROR_ATTRIBUTE
                            );
                            String requestedMessage = (String) request.getAttribute(
                                    PlatformJwtFilter.AUTH_ERROR_MESSAGE_ATTRIBUTE
                            );
                            ErrorCode code = ErrorCode.TOKEN_INVALID.code().equals(requestedCode)
                                    ? ErrorCode.TOKEN_INVALID
                                    : ErrorCode.UNAUTHORIZED;
                            writeError(request, response, code, requestedMessage);
                        })
                        .accessDeniedHandler((request, response, exception) ->
                                writeError(request, response, ErrorCode.PERMISSION_DENIED, null))
                )
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private void writeError(
            jakarta.servlet.http.HttpServletRequest request,
            jakarta.servlet.http.HttpServletResponse response,
            ErrorCode code,
            String message
    ) throws IOException {
        String traceId = (String) request.getAttribute(TraceIdFilter.ATTRIBUTE_NAME);
        if (traceId == null || traceId.isBlank()) {
            traceId = "tr_" + UUID.randomUUID().toString().replace("-", "");
        }
        response.setStatus(code.httpStatus());
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(TraceIdFilter.HEADER_NAME, traceId);
        objectMapper.writeValue(
                response.getWriter(),
                ApiResponse.error(code, message, traceId, clock)
        );
    }
}
