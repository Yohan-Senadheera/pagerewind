package dev.pagerewind.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param publicPaths request paths that need no token, because they are public by design or check their
 *                    own credentials (for example, signed links)
 */
@ConfigurationProperties("pagerewind.security")
public record SecurityProperties(List<String> publicPaths) {

    public SecurityProperties {
        publicPaths = publicPaths == null ? List.of() : List.copyOf(publicPaths);
    }
}
