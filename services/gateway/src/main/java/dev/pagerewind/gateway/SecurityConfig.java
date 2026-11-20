package dev.pagerewind.gateway;

import java.net.URI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator;
import org.springframework.security.oauth2.client.oidc.authentication.ReactiveOidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.server.DefaultServerOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoderFactory;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.logout.ServerLogoutSuccessHandler;
import org.springframework.security.web.server.csrf.CsrfWebFilter;
import org.springframework.security.web.server.util.matcher.AndServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.NegatedServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
class SecurityConfig {

    static final String[] OPEN = {"/actuator/health/**", "/actuator/info", "/actuator/prometheus"};

    /**
     * Requests the gateway forwards without a login: the service behind each one checks its own credential
     * (a bearer token, an alert signature, Telegram's secret header, or a signed single-use link). No session
     * token is ever attached to them, so they need no CSRF token either.
     */
    static final String[] PASS_THROUGH = {"/api/**", "/v1/alerts/**", "/v1/telegram/webhook", "/v1/actions/**"};

    @Bean
    SecurityWebFilterChain web(ServerHttpSecurity http, ReactiveClientRegistrationRepository registrations,
            GatewayProperties properties) {
        ServerWebExchangeMatcher passThrough = ServerWebExchangeMatchers.pathMatchers(PASS_THROUGH);
        DefaultServerOAuth2AuthorizationRequestResolver pkce = new DefaultServerOAuth2AuthorizationRequestResolver(registrations);
        pkce.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());

        http.authorizeExchange(exchanges -> exchanges
                        .pathMatchers(OPEN).permitAll()
                        .matchers(passThrough).permitAll()
                        .pathMatchers("/", "/ui", "/ui/**").authenticated()
                        .anyExchange().denyAll())
                .oauth2Login(login -> login.authorizationRequestResolver(pkce))
                .logout(logout -> logout.logoutSuccessHandler(keycloakLogout(properties)))
                .csrf(csrf -> csrf.requireCsrfProtectionMatcher(new AndServerWebExchangeMatcher(
                        CsrfWebFilter.DEFAULT_CSRF_MATCHER, new NegatedServerWebExchangeMatcher(passThrough))));
        return http.build();
    }

    /** Checks that ID tokens come from the expected realm, not only that Keycloak signed them. */
    @Bean
    ReactiveJwtDecoderFactory<ClientRegistration> idTokenDecoderFactory(GatewayProperties properties) {
        ReactiveOidcIdTokenDecoderFactory factory = new ReactiveOidcIdTokenDecoderFactory();
        factory.setJwtValidatorFactory(registration -> new DelegatingOAuth2TokenValidator<>(
                new OidcIdTokenValidator(registration), new JwtIssuerValidator(properties.issuer())));
        return factory;
    }

    /** Ends the Keycloak session too, then comes back to the gateway, which asks for a new login. */
    private static ServerLogoutSuccessHandler keycloakLogout(GatewayProperties properties) {
        return (exchange, authentication) -> {
            URI home = UriComponentsBuilder.fromUri(exchange.getExchange().getRequest().getURI())
                    .replacePath("/").replaceQuery(null).build().toUri();
            UriComponentsBuilder logout = UriComponentsBuilder
                    .fromUriString(properties.issuer() + "/protocol/openid-connect/logout")
                    .queryParam("client_id", "pagerewind-web")
                    .queryParam("post_logout_redirect_uri", home.toString());
            if (authentication.getPrincipal() instanceof OidcUser user) {
                logout.queryParam("id_token_hint", user.getIdToken().getTokenValue());
            }
            ServerHttpResponse response = exchange.getExchange().getResponse();
            response.setStatusCode(HttpStatus.FOUND);
            response.getHeaders().setLocation(logout.encode().build().toUri());
            return Mono.empty();
        };
    }
}
