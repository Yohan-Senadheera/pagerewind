package dev.pagerewind.gateway;

import java.net.InetSocketAddress;
import java.net.URI;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.support.ipresolver.RemoteAddressResolver;
import org.springframework.cloud.gateway.support.ipresolver.XForwardedRemoteAddressResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

@Configuration(proxyBeanMethods = false)
class RoutingConfig {

    @Bean
    RouterFunction<ServerResponse> home() {
        return RouterFunctions.route(RequestPredicates.GET("/"), request -> ServerResponse.seeOther(URI.create("/ui")).build());
    }

    /**
     * Rate limits count per client address. Behind proxies, the address comes from X-Forwarded-For, trusting
     * only the entries those proxies added, so a client cannot choose its own bucket.
     */
    @Bean
    KeyResolver clientAddress(GatewayProperties properties) {
        RemoteAddressResolver resolver = properties.trustedProxies() > 0
                ? XForwardedRemoteAddressResolver.maxTrustedIndex(properties.trustedProxies())
                : new RemoteAddressResolver() {
                };
        return exchange -> {
            InetSocketAddress address = resolver.resolve(exchange);
            return Mono.just(address == null || address.getAddress() == null ? "unknown" : address.getAddress().getHostAddress());
        };
    }
}
