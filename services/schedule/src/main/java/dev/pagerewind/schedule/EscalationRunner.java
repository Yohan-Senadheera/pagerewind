package dev.pagerewind.schedule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Checks for due escalation steps every second. Tests turn this off and call the service directly. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "pagerewind.schedule.escalation-runner-enabled", havingValue = "true", matchIfMissing = true)
class EscalationRunner {

    private static final Logger log = LoggerFactory.getLogger(EscalationRunner.class);

    private final EscalationService service;

    EscalationRunner(EscalationService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${pagerewind.schedule.escalation-poll-interval-ms:1000}")
    void run() {
        try {
            service.fireDue();
        } catch (RuntimeException e) {
            log.warn("Escalation run failed: {}", e.toString());
        }
    }
}
