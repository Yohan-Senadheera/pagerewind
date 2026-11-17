package dev.pagerewind.notify;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/** Calls the incident service to acknowledge or resolve on behalf of the person who was paged. */
@Component
public class IncidentClient {

    public enum Result { DONE, ALREADY_DONE, NOT_FOUND, UNAVAILABLE }

    private final RestClient rest;

    public IncidentClient(NotifyProperties properties, RestClient.Builder builder) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.rest = builder.baseUrl(properties.incidentBaseUrl()).requestFactory(factory).build();
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
        } catch (ResourceAccessException e) {
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
