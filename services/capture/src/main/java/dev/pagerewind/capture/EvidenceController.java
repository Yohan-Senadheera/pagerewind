package dev.pagerewind.capture;

import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Evidence bundles hold log lines, so only responders may download them. The same bundle is served for
 * the API and for the web UI (through the gateway, which forwards the signed-in person's token).
 */
@RestController
class EvidenceController {

    private final EvidenceService evidence;

    EvidenceController(EvidenceService evidence) {
        this.evidence = evidence;
    }

    @GetMapping({"/v1/evidence/{incidentId}", "/ui/evidence/{incidentId}"})
    @PreAuthorize("hasRole('responder')")
    ResponseEntity<byte[]> download(@PathVariable UUID incidentId) {
        return evidence.find(incidentId)
                .map(found -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType("application/zip"))
                        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                                .filename("pagerewind-evidence-" + incidentId + ".zip").build().toString())
                        .header(HttpHeaders.CACHE_CONTROL, "no-store")
                        .body(found.bundle()))
                .orElse(ResponseEntity.notFound().build());
    }
}
