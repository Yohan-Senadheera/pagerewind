package dev.pagerewind.status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class FaultInjectionFilterTest {

    private static MockHttpServletResponse call(FaultInjectionFilter filter, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void offByDefault() throws Exception {
        assertThat(call(new FaultInjectionFilter(0, () -> 0), "/v1/status").getStatus()).isEqualTo(200);
    }

    @Test
    void failsTheShareOfStatusRequestsBelowThePercent() throws Exception {
        assertThat(call(new FaultInjectionFilter(30, () -> 29), "/v1/status").getStatus()).isEqualTo(503);
        assertThat(call(new FaultInjectionFilter(30, () -> 29), "/").getStatus()).isEqualTo(503);
        assertThat(call(new FaultInjectionFilter(30, () -> 30), "/v1/status").getStatus()).isEqualTo(200);
    }

    @Test
    void neverTouchesHealthOrMetrics() throws Exception {
        FaultInjectionFilter always = new FaultInjectionFilter(100, () -> 0);
        assertThat(call(always, "/actuator/health/readiness").getStatus()).isEqualTo(200);
        assertThat(call(always, "/actuator/prometheus").getStatus()).isEqualTo(200);
    }

    @Test
    void rejectsPercentOutsideZeroToHundred() {
        assertThatThrownBy(() -> new FaultInjectionFilter(101, () -> 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FaultInjectionFilter(-1, () -> 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
