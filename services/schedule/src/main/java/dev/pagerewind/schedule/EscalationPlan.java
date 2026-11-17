package dev.pagerewind.schedule;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Turns a policy into concrete timers for one incident. */
public final class EscalationPlan {

    public record PlannedJob(int stepNo, int round, EscalationStep.TargetType targetType, String targetId, Instant fireAt) {
    }

    private EscalationPlan() {
    }

    /**
     * Step 1 fires at {@code start}. Each later step fires when the previous one's wait has passed.
     * After the last step, the whole policy runs again {@code repeat} more times.
     */
    public static List<PlannedJob> plan(List<EscalationStep> steps, int repeat, Instant start) {
        List<PlannedJob> jobs = new ArrayList<>();
        Duration offset = Duration.ZERO;
        for (int round = 1; round <= repeat + 1; round++) {
            for (EscalationStep step : steps) {
                jobs.add(new PlannedJob(step.stepNo(), round, step.targetType(), step.targetId(), start.plus(offset)));
                offset = offset.plusMinutes(step.escalateAfterMinutes());
            }
        }
        return jobs;
    }
}
