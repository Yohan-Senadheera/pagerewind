package dev.pagerewind.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param issuer         the Keycloak realm as tokens name it (its public address)
 * @param backchannel    the same realm as the gateway reaches it
 * @param trustedProxies proxies in front of the gateway that append to X-Forwarded-For
 */
@ConfigurationProperties("pagerewind.gateway")
public record GatewayProperties(String issuer, String backchannel, int trustedProxies) {
}
