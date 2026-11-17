package dev.pagerewind.schedule;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Works out who is on call for a rotation at a moment in time. */
public final class OnCall {

    /**
     * @param override true when an override, not the normal rotation, decides this shift
     */
    public record Shift(String personId, Instant start, Instant end, boolean override) {
    }

    private OnCall() {
    }

    public static Optional<Shift> at(Rotation rotation, List<RotationOverride> overrides, Instant at) {
        Optional<RotationOverride> override = overrides.stream()
                .filter(o -> o.rotationId().equals(rotation.id()) && o.covers(at))
                .max(Comparator.comparingLong(RotationOverride::id));
        if (override.isPresent()) {
            RotationOverride o = override.get();
            return Optional.of(new Shift(o.personId(), o.start(), o.end(), true));
        }
        if (rotation.members().isEmpty()) {
            return Optional.empty();
        }
        ZonedDateTime first = rotation.start().atZone(rotation.zone());
        if (at.isBefore(first.toInstant())) {
            return Optional.empty();
        }
        long n = shiftIndex(first, rotation.shiftHours(), at);
        String person = rotation.members().get((int) (n % rotation.members().size()));
        return Optional.of(new Shift(person, shiftStart(first, rotation.shiftHours(), n).toInstant(),
                shiftStart(first, rotation.shiftHours(), n + 1).toInstant(), false));
    }

    static ZonedDateTime shiftStart(ZonedDateTime first, int shiftHours, long n) {
        if (shiftHours % 24 == 0) {
            return first.plusDays(n * (shiftHours / 24));
        }
        return first.plusHours(n * shiftHours);
    }

    private static long shiftIndex(ZonedDateTime first, int shiftHours, Instant at) {
        long n = Duration.between(first.toInstant(), at).toHours() / shiftHours;
        while (n > 0 && shiftStart(first, shiftHours, n).toInstant().isAfter(at)) {
            n--;
        }
        while (!shiftStart(first, shiftHours, n + 1).toInstant().isAfter(at)) {
            n++;
        }
        return n;
    }
}
