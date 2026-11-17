package dev.pagerewind.schedule;

/**
 * One step of a team's escalation policy: page {@code target}, and if nobody acknowledges within
 * {@code escalateAfterMinutes}, move to the next step.
 */
public record EscalationStep(int stepNo, int escalateAfterMinutes, TargetType targetType, String targetId) {

    public enum TargetType {
        /** Whoever is on call in the rotation when the step fires. */
        ROTATION,
        /** A fixed person. */
        PERSON
    }
}
