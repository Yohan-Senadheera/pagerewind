package dev.pagerewind.status;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Chaos testing: answers a share of status page requests with 503, to rehearse a bad release (canary
 * analysis, alerts, rollback). Off unless {@code pagerewind.status.fault-percent} is above 0. Health
 * and metrics endpoints are never affected, so the pod still looks healthy to Kubernetes, which is what
 * a real bad release often looks like.
 */
@Component
class FaultInjectionFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(FaultInjectionFilter.class);

    private final int percent;
    private final IntSupplier roll;

    @Autowired
    FaultInjectionFilter(@Value("${pagerewind.status.fault-percent:0}") int percent) {
        this(percent, () -> ThreadLocalRandom.current().nextInt(100));
    }

    FaultInjectionFilter(int percent, IntSupplier roll) {
        if (percent < 0 || percent > 100) {
            throw new IllegalArgumentException("pagerewind.status.fault-percent must be 0 to 100, was " + percent);
        }
        this.percent = percent;
        this.roll = roll;
        if (percent > 0) {
            log.warn("Fault injection is ON: {}% of status page requests will fail with 503", percent);
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return percent == 0 || !(path.equals("/") || path.equals("/v1/status"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (roll.getAsInt() < percent) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Injected fault");
            return;
        }
        chain.doFilter(request, response);
    }
}
