package dev.pagerewind.schedule;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * A rotation hands on-call duty from member to member every {@code shiftHours}.
 *
 * <p>The start is a local date and time in {@code zone}. Shifts that are whole days hand over at
 * the same local clock time every day, also across daylight saving changes. Other lengths are
 * measured in real hours.
 */
public record Rotation(String id, String teamId, String name, List<String> members, LocalDateTime start,
        ZoneId zone, int shiftHours) {

    public Rotation {
        members = List.copyOf(members);
    }
}
