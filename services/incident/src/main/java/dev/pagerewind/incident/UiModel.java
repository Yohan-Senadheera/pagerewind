package dev.pagerewind.incident;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Values every page needs: who is signed in, what they may do, and the gateway's CSRF token for forms. */
@ControllerAdvice(assignableTypes = IncidentUiController.class)
class UiModel {

    /** The gateway sends its CSRF token in this header; pages put it in their forms so the gateway accepts them. */
    static final String CSRF_HEADER = "X-PageRewind-Csrf";

    private final RoleHierarchy roles;

    UiModel(RoleHierarchy roles) {
        this.roles = roles;
    }

    @ModelAttribute("currentUser")
    String currentUser(Authentication caller) {
        return caller == null ? null : caller.getName();
    }

    @ModelAttribute("canRespond")
    boolean canRespond(Authentication caller) {
        return caller != null && roles.getReachableGrantedAuthorities(caller.getAuthorities()).stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_responder"::equals);
    }

    @ModelAttribute("csrfToken")
    String csrfToken(HttpServletRequest request) {
        return request.getHeader(CSRF_HEADER);
    }
}
