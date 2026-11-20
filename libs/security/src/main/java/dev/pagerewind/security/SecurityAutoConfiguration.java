package dev.pagerewind.security;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Every API needs a valid Keycloak access token (issuer, audience, signature and expiry are checked by
 * Spring Boot from {@code spring.security.oauth2.resourceserver.jwt.*}). Reading needs the role
 * {@code viewer}; {@code responder} and {@code admin} include it.
 */
@AutoConfiguration(beforeName = {
        "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.actuate.web.servlet.ManagementWebSecurityAutoConfiguration",
        "org.springframework.boot.security.oauth2.server.resource.autoconfigure.web.OAuth2ResourceServerWebSecurityAutoConfiguration"})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(SecurityProperties.class)
@EnableMethodSecurity
public class SecurityAutoConfiguration {

    public static final String ADMIN = "admin";
    public static final String RESPONDER = "responder";
    public static final String VIEWER = "viewer";

    static final String[] ALWAYS_PUBLIC = {
        "/error",
        "/actuator/health/**",
        "/actuator/info",
        "/actuator/prometheus",
        "/v3/api-docs/**",
        "/swagger-ui.html",
        "/swagger-ui/**",
    };

    @Bean
    @ConditionalOnMissingBean
    static RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.withDefaultRolePrefix()
                .role(ADMIN).implies(RESPONDER)
                .role(RESPONDER).implies(VIEWER)
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    SecurityFilterChain apiSecurity(HttpSecurity http, SecurityProperties properties) throws Exception {
        http.authorizeHttpRequests(requests -> requests
                        .requestMatchers(ALWAYS_PUBLIC).permitAll()
                        .requestMatchers(properties.publicPaths().toArray(String[]::new)).permitAll()
                        .anyRequest().hasRole(VIEWER))
                .oauth2ResourceServer(server -> server.jwt(jwt -> jwt.jwtAuthenticationConverter(new KeycloakRoles())))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Bearer tokens only, never cookies, so cross-site requests cannot borrow a login. The gateway
                // checks CSRF tokens for browser sessions before it attaches a token.
                .csrf(csrf -> csrf.disable());
        return http.build();
    }
}
