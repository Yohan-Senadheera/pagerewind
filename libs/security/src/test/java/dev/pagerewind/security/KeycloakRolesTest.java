package dev.pagerewind.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class KeycloakRolesTest {

    private final KeycloakRoles converter = new KeycloakRoles();

    private static Jwt.Builder jwt() {
        return Jwt.withTokenValue("t").header("alg", "RS256").subject("4d2c").issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60));
    }

    @Test
    void realmRolesBecomeSpringRolesAndTheUsernameIsTheName() {
        AbstractAuthenticationToken auth = converter.convert(jwt().claim("preferred_username", "alice")
                .claim("realm_access", Map.of("roles", List.of("responder", "offline_access")))
                .claim("resource_access", Map.of("account", Map.of("roles", List.of("manage-account"))))
                .build());

        assertThat(auth.getName()).isEqualTo("alice");
        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_responder", "ROLE_offline_access");
    }

    @Test
    void tokensWithoutRolesOrUsernameStillWork() {
        AbstractAuthenticationToken auth = converter.convert(jwt().claim("scope", "openid").build());

        assertThat(auth.getName()).isEqualTo("4d2c");
        assertThat(auth.getAuthorities()).isEmpty();
    }

    @Test
    void malformedRoleClaimsAreIgnored() {
        AbstractAuthenticationToken auth = converter.convert(jwt().claim("preferred_username", "bob")
                .claim("realm_access", Map.of("roles", "admin")).build());

        assertThat(auth.getAuthorities()).isEmpty();
    }

    @Test
    void onlyServicesMayActForSomeoneElse() {
        AbstractAuthenticationToken person = converter.convert(jwt().claim("preferred_username", "mallory").build());
        AbstractAuthenticationToken service = converter.convert(
                jwt().claim("preferred_username", "service-account-notify-svc").build());

        assertThat(Callers.isService(person)).isFalse();
        assertThat(Callers.actor(person, "alice")).isEqualTo("mallory");
        assertThat(Callers.isService(service)).isTrue();
        assertThat(Callers.actor(service, " alice ")).isEqualTo("alice");
        assertThat(Callers.actor(service, " ")).isEqualTo("service-account-notify-svc");
        assertThat(Callers.actor(service, null)).isEqualTo("service-account-notify-svc");
    }
}
