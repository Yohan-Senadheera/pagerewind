package dev.pagerewind.schedule;

import static dev.pagerewind.schedule.EscalationStep.TargetType.PERSON;
import static dev.pagerewind.schedule.EscalationStep.TargetType.ROTATION;
import static org.assertj.core.api.Assertions.assertThat;

import dev.pagerewind.schedule.EscalationPlan.PlannedJob;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class EscalationPlanTest {

    private static final Instant T0 = Instant.parse("2026-11-18T10:00:00Z");
    private static final List<EscalationStep> STEPS = List.of(
            new EscalationStep(1, 2, ROTATION, "primary"),
            new EscalationStep(2, 5, PERSON, "bob"),
            new EscalationStep(3, 10, PERSON, "lead"));

    @Test
    void firstStepFiresImmediatelyAndEachWaitAddsUp() {
        List<PlannedJob> jobs = EscalationPlan.plan(STEPS, 0, T0);

        assertThat(jobs).extracting(PlannedJob::fireAt)
                .containsExactly(T0, T0.plusSeconds(120), T0.plusSeconds(420));
        assertThat(jobs).extracting(PlannedJob::round).containsOnly(1);
        assertThat(jobs.get(0).targetType()).isEqualTo(ROTATION);
        assertThat(jobs.get(0).targetId()).isEqualTo("primary");
    }

    @Test
    void repeatRunsThePolicyAgainAfterTheLastWait() {
        List<PlannedJob> jobs = EscalationPlan.plan(STEPS, 2, T0);

        assertThat(jobs).hasSize(9);
        assertThat(jobs).extracting(PlannedJob::round).containsExactly(1, 1, 1, 2, 2, 2, 3, 3, 3);
        // One round lasts 2 + 5 + 10 = 17 minutes.
        assertThat(jobs.get(3).fireAt()).isEqualTo(T0.plusSeconds(17 * 60));
        assertThat(jobs.get(6).fireAt()).isEqualTo(T0.plusSeconds(34 * 60));
        assertThat(jobs.get(8).stepNo()).isEqualTo(3);
    }

    @Test
    void emptyPolicyPlansNothing() {
        assertThat(EscalationPlan.plan(List.of(), 3, T0)).isEmpty();
    }
}
