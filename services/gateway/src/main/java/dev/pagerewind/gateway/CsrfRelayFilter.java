package dev.pagerewind.gateway;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Lets server-rendered pages post forms that the gateway accepts. The services render the pages but do
 * not hold the session, so the gateway passes its CSRF token to them in {@link #HEADER} and they put it in
 * each form. A value sent by the browser in that header is always removed.
 *
 * <p>Checking the CSRF token reads the form body, so the gateway forwards a copy of the form without the
 * {@code _csrf} field.
 */
@Component
class CsrfRelayFilter implements GlobalFilter, Ordered {

    static final String HEADER = "X-PageRewind-Csrf";
    static final String FORM_FIELD = "_csrf";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest().mutate().headers(headers -> headers.remove(HEADER)).build();
        ServerWebExchange cleaned = exchange.mutate().request(request).build();
        Mono<CsrfToken> token = exchange.getAttribute(CsrfToken.class.getName());
        if (!isPage(request) || token == null) {
            return chain.filter(cleaned);
        }
        return token.flatMap(csrf -> withoutCsrfField(cleaned)
                        .map(ready -> ready.mutate().request(r -> r.headers(h -> h.set(HEADER, csrf.getToken()))).build()))
                .flatMap(chain::filter);
    }

    private static boolean isPage(ServerHttpRequest request) {
        String path = request.getPath().pathWithinApplication().value();
        return path.equals("/ui") || path.startsWith("/ui/");
    }

    private static Mono<ServerWebExchange> withoutCsrfField(ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest();
        if (request.getMethod() != HttpMethod.POST
                || !MediaType.APPLICATION_FORM_URLENCODED.isCompatibleWith(request.getHeaders().getContentType())) {
            return Mono.just(exchange);
        }
        return exchange.getFormData().map(form -> {
            byte[] body = encode(form).getBytes(StandardCharsets.UTF_8);
            HttpHeaders headers = new HttpHeaders();
            headers.putAll(request.getHeaders());
            headers.setContentLength(body.length);
            ServerHttpRequest copy = new ServerHttpRequestDecorator(request) {
                @Override
                public HttpHeaders getHeaders() {
                    return headers;
                }

                @Override
                public Flux<DataBuffer> getBody() {
                    return Flux.defer(() -> Flux.just(exchange.getResponse().bufferFactory().wrap(body)));
                }
            };
            return exchange.mutate().request(copy).build();
        });
    }

    static String encode(MultiValueMap<String, String> form) {
        StringJoiner joined = new StringJoiner("&");
        for (Map.Entry<String, List<String>> field : form.entrySet()) {
            if (field.getKey().equals(FORM_FIELD)) {
                continue;
            }
            for (String value : field.getValue()) {
                joined.add(URLEncoder.encode(field.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8));
            }
        }
        return joined.toString();
    }

    /** Before the routing filters, which send the request on. */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }
}
