package dev.pagerewind.notify;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Calls the incident service to acknowledge or resolve on behalf of the person who was paged. Each call
 * carries notify's own Keycloak token (client credentials); the incident service records the paged person
 * as the actor because the caller is a service.
 */
@Component
public class IncidentClient {

    public enum Result { DONE, ALREADY_DONE, NOT_FOUND, UNAVAILABLE }

    /** The client registration under {@code spring.security.oauth2.client.registration}. */
    static final String REGISTRATION = "notify-svc";

    private static final Authentication SELF = new AnonymousAuthenticationToken(REGISTRATION, REGISTRATION,
            AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

    private final RestClient rest;

    public IncidentClient(NotifyProperties properties, RestClient.Builder builder, OAuth2AuthorizedClientManager tokens,
            OAuth2AuthorizedClientService tokenCache) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        OAuth2ClientHttpRequestInterceptor withToken = new OAuth2ClientHttpRequestInterceptor(tokens);
        withToken.setClientRegistrationIdResolver(request -> REGISTRATION);
        withToken.setPrincipalResolver(request -> SELF);
        // A 401 or 403 from the incident service drops the cached token, so the next call fetches a fresh one.
        withToken.setAuthorizationFailureHandler(OAuth2ClientHttpRequestInterceptor.authorizationFailureHandler(tokenCache));
        this.rest = builder.baseUrl(properties.incidentBaseUrl()).requestFactory(factory).requestInterceptor(withToken).build();
    }

    public Result apply(UUID incidentId, ActionTokens.Action action, String actor) {
        String path = action == ActionTokens.Action.ACKNOWLEDGE ? "acknowledge" : "resolve";
        try {
            rest.post().uri("/v1/incidents/{id}/{action}", incidentId, path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("actor", actor))
                    .retrieve().toBodilessEntity();
            return Result.DONE;
        } catch (HttpStatusCodeException e) {
            return switch (e.getStatusCode().value()) {
                case 409 -> Result.ALREADY_DONE;
                case 404 -> Result.NOT_FOUND;
                default -> Result.UNAVAILABLE;
            };
        } catch (ResourceAccessException | OAuth2AuthorizationException e) {
            // Incident service or Keycloak unreachable, or Keycloak refused notify's credentials.
            return Result.UNAVAILABLE;
        }
    }

    static String describe(Result result, ActionTokens.Action action) {
        String done = action == ActionTokens.Action.ACKNOWLEDGE ? "acknowledged" : "resolved";
        return switch (result) {
            case DONE -> "Incident " + done + ".";
            case ALREADY_DONE -> "Nothing to do: the incident was already " + done + " or closed.";
            case NOT_FOUND -> "This incident does not exist.";
            case UNAVAILABLE -> "PageRewind could not reach the incident service. Try again in a minute.";
        };
    }
}
