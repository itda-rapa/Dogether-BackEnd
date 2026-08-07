package itda.common.config;

import itda.common.constants.ErrorCode;
import itda.common.dto.ApiResponse;
import itda.common.filter.JwtFilter;
import itda.common.properties.CorsProperties;
import itda.common.properties.AdminBootstrapProperties;
import itda.common.properties.JwtProperties;
import itda.common.properties.MediaProperties;
import itda.common.properties.S3Properties;
import java.io.IOException;
import java.util.List;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyAuthoritiesMapper;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties({
        JwtProperties.class,
        S3Properties.class,
        MediaProperties.class,
        AdminBootstrapProperties.class,
        CorsProperties.class
})
public class SecurityConfig {

    private final JwtFilter jwtFilter;
    private final ObjectMapper objectMapper;
    private final CorsProperties corsProperties;

    public SecurityConfig(
            JwtFilter jwtFilter,
            ObjectMapper objectMapper,
            CorsProperties corsProperties
    ) {
        this.jwtFilter = jwtFilter;
        this.objectMapper = objectMapper;
        this.corsProperties = corsProperties;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint((request, response, cause) ->
                                writeError(response, ErrorCode.UNAUTHORIZED))
                        .accessDeniedHandler((request, response, cause) ->
                                writeError(response, ErrorCode.FORBIDDEN)))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/actuator/health",
                                "/media-test",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html"
                        ).permitAll()
                        .requestMatchers(
                                HttpMethod.POST,
                                "/auth/signup",
                                "/auth/login",
                                "/auth/refresh",
                                "/auth/email-verifications",
                                "/auth/email-verifications/confirm"
                        ).permitAll()
                        .requestMatchers(
                                HttpMethod.GET,
                                "/neighborhoods"
                        ).permitAll()
                        .requestMatchers("/admin/**")
                        .hasAnyRole("ADMIN", "SUPER_ADMIN")
                        .anyRequest().authenticated())
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.fromHierarchy(
                "ROLE_SUPER_ADMIN > ROLE_ADMIN\nROLE_ADMIN > ROLE_USER"
        );
    }

    @Bean
    GrantedAuthoritiesMapper grantedAuthoritiesMapper(RoleHierarchy roleHierarchy) {
        return new RoleHierarchyAuthoritiesMapper(roleHierarchy);
    }

    @Bean
    UrlBasedCorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        List<String> allowedOrigins = corsProperties.allowedOrigins();
        configuration.setAllowedOrigins(
                allowedOrigins == null ? List.of() : allowedOrigins
        );
        configuration.setAllowedMethods(
                List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
        );
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setExposedHeaders(List.of("Authorization"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    private void writeError(
            HttpServletResponse response,
            ErrorCode errorCode
    ) throws IOException {
        response.setStatus(errorCode.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(
                response.getOutputStream(),
                ApiResponse.fail(errorCode.name(), errorCode.getDescription())
        );
    }
}
