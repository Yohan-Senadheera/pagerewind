package dev.pagerewind.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class OnCallTest {

    private static final ZoneId COLOMBO = ZoneId.of("Asia/Colombo");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private static Rotation rotation(ZoneId zone, LocalDateTime start, int hours, String... members) {
        return new Rotation("primary", "ops", "Primary", List.of(members), start, zone, hours);
    }

    private static Instant local(ZoneId zone, String dateTime) {
        return LocalDateTime.parse(dateTime).atZone(zone).toInstant();
    }

    private static String who(Rotation rotation, Instant at) {
        return OnCall.at(rotation, List.of(), at).map(OnCall.Shift::personId).orElse(null);
    }

    @Test
    void dailyRotationHandsOverAtTheStartTime() {
        Rotation r = rotation(COLOMBO, LocalDateTime.parse("2026-11-18T09:00"), 24, "alice", "bob", "carol");

        assertThat(who(r, local(COLOMBO, "2026-11-18T09:00"))).isEqualTo("alice");
        assertThat(who(r, local(COLOMBO, "2026-11-19T08:59:59"))).isEqualTo("alice");
        assertThat(who(r, local(COLOMBO, "2026-11-19T09:00"))).isEqualTo("bob");
        assertThat(who(r, local(COLOMBO, "2026-11-20T23:00"))).isEqualTo("carol");
        assertThat(who(r, local(COLOMBO, "2026-11-21T09:00"))).isEqualTo("alice");
        // 365 days later: 365 % 3 = 2, so carol.
        assertThat(who(r, local(COLOMBO, "2027-11-18T09:00"))).isEqualTo("carol");
    }

    @Test
    void nobodyIsOnCallBeforeTheRotationStarts() {
        Rotation r = rotation(COLOMBO, LocalDateTime.parse("2026-11-18T09:00"), 24, "alice");
        assertThat(OnCall.at(r, List.of(), local(COLOMBO, "2026-11-18T08:59:59"))).isEmpty();
    }

    @Test
    void emptyRotationHasNobody() {
        Rotation r = rotation(COLOMBO, LocalDateTime.parse("2026-11-18T09:00"), 24);
        assertThat(OnCall.at(r, List.of(), local(COLOMBO, "2026-11-19T09:00"))).isEmpty();
    }

    @Test
    void shiftReportsItsStartAndEnd() {
        Rotation r = rotation(COLOMBO, LocalDateTime.parse("2026-11-18T09:00"), 24, "alice", "bob");
        OnCall.Shift shift = OnCall.at(r, List.of(), local(COLOMBO, "2026-11-19T15:00")).orElseThrow();

        assertThat(shift.personId()).isEqualTo("bob");
        assertThat(shift.start()).isEqualTo(local(COLOMBO, "2026-11-19T09:00"));
        assertThat(shift.end()).isEqualTo(local(COLOMBO, "2026-11-20T09:00"));
        assertThat(shift.override()).isFalse();
    }

    @Test
    void dailyHandoverKeepsLocalTimeAcrossDaylightSavingChanges() {
        // New York moves clocks back on 1 Nov 2026 and forward on 14 Mar 2027.
        Rotation r = rotation(NEW_YORK, LocalDateTime.parse("2026-10-30T09:00"), 24, "alice", "bob");

        OnCall.Shift afterFallBack = OnCall.at(r, List.of(), local(NEW_YORK, "2026-11-02T12:00")).orElseThrow();
        assertThat(afterFallBack.start().atZone(NEW_YORK).toLocalTime().toString()).isEqualTo("09:00");
        assertThat(afterFallBack.end().atZone(NEW_YORK).toLocalTime().toString()).isEqualTo("09:00");

        OnCall.Shift springForward = OnCall.at(r, List.of(), local(NEW_YORK, "2027-03-14T12:00")).orElseThrow();
        assertThat(springForward.start().atZone(NEW_YORK).toLocalTime().toString()).isEqualTo("09:00");
        assertThat(java.time.Duration.between(springForward.start(), springForward.end()).toHours()).isEqualTo(24);
        // The shift that contains the clock change is 23 hours long.
        OnCall.Shift containing = OnCall.at(r, List.of(), local(NEW_YORK, "2027-03-14T05:00")).orElseThrow();
        assertThat(java.time.Duration.between(containing.start(), containing.end()).toHours()).isEqualTo(23);

        // 30 Oct is day 0 (alice); 1 Nov is day 2 (alice); 2 Nov is day 3 (bob).
        assertThat(who(r, local(NEW_YORK, "2026-11-01T10:00"))).isEqualTo("alice");
        assertThat(who(r, local(NEW_YORK, "2026-11-02T08:59"))).isEqualTo("alice");
        assertThat(who(r, local(NEW_YORK, "2026-11-02T09:00"))).isEqualTo("bob");
    }

    @Test
    void shortShiftsCountRealHours() {
        Rotation r = rotation(NEW_YORK, LocalDateTime.parse("2026-11-01T00:00"), 12, "day", "night");
        // 1 Nov has 25 hours in New York, so 12-hour shifts drift by an hour on the local clock.
        assertThat(who(r, local(NEW_YORK, "2026-11-01T00:30"))).isEqualTo("day");
        assertThat(who(r, local(NEW_YORK, "2026-11-01T11:30"))).isEqualTo("night");
        assertThat(who(r, local(NEW_YORK, "2026-11-01T23:30"))).isEqualTo("day");
    }

    @Test
    void weeklyRotation() {
        Rotation r = rotation(COLOMBO, LocalDateTime.parse("2026-11-16T10:00"), 168, "alice", "bob");
        assertThat(who(r, local(COLOMBO, "2026-11-22T23:00"))).isEqualTo("alice");
        assertThat(who(r, local(COLOMBO, "2026-11-23T10:00"))).isEqualTo("bob");
        assertThat(who(r, local(COLOMBO, "2026-11-30T09:59"))).isEqualTo("bob");
        assertThat(who(r, local(COLOMBO, "2026-11-30T10:00"))).isEqualTo("alice");
    }

    @Test
    void overrideWinsOnlyDuringItsWindow() {
        Rotation r = rotation(COLOMBO, LocalDateTime.parse("2026-11-18T09:00"), 24, "alice", "bob");
        Instant from = local(COLOMBO, "2026-11-18T12:00");
        Instant until = local(COLOMBO, "2026-11-18T14:00");
        List<RotationOverride> overrides = List.of(new RotationOverride(1, "primary", "dave", from, until));

        assertThat(OnCall.at(r, overrides, from.minusSeconds(1)).orElseThrow().personId()).isEqualTo("alice");
        OnCall.Shift covered = OnCall.at(r, overrides, from).orElseThrow();
        assertThat(covered.personId()).isEqualTo("dave");
        assertThat(covered.override()).isTrue();
        assertThat(covered.start()).isEqualTo(from);
        assertThat(covered.end()).isEqualTo(until);
        assertThat(OnCall.at(r, overrides, until).orElseThrow().personId()).isEqualTo("alice");
    }

    @Test
    void newestOverlappingOverrideWins() {
        Rotation r = rotation(COLOMBO, LocalDateTime.parse("2026-11-18T09:00"), 24, "alice");
        Instant at = local(COLOMBO, "2026-11-18T13:00");
        List<RotationOverride> overrides = List.of(
                new RotationOverride(7, "primary", "newer", at.minusSeconds(60), at.plusSeconds(60)),
                new RotationOverride(3, "primary", "older", at.minusSeconds(3600), at.plusSeconds(3600)));

        assertThat(OnCall.at(r, overrides, at).orElseThrow().personId()).isEqualTo("newer");
    }

    @Test
    void overrideCoversRotationBeforeItStarts() {
        Rotation r = rotation(COLOMBO, LocalDateTime.parse("2026-12-01T09:00"), 24, "alice");
        Instant at = local(COLOMBO, "2026-11-20T09:00");
        List<RotationOverride> overrides = List.of(new RotationOverride(1, "primary", "dave", at, at.plusSeconds(3600)));
        assertThat(OnCall.at(r, overrides, at).orElseThrow().personId()).isEqualTo("dave");
    }

    @Test
    void overridesForOtherRotationsAreIgnored() {
        Rotation r = rotation(COLOMBO, LocalDateTime.parse("2026-11-18T09:00"), 24, "alice");
        Instant at = local(COLOMBO, "2026-11-18T13:00");
        List<RotationOverride> overrides = List.of(new RotationOverride(1, "secondary", "dave", at, at.plusSeconds(60)));
        assertThat(OnCall.at(r, overrides, at).orElseThrow().personId()).isEqualTo("alice");
    }

    @Test
    void farFutureIsComputedWithoutLooping() {
        Rotation r = rotation(COLOMBO, LocalDateTime.parse("2026-11-18T09:00"), 1, "a", "b", "c");
        Instant at = ZonedDateTime.of(2036, 11, 18, 9, 30, 0, 0, COLOMBO).toInstant();
        long hours = java.time.Duration.between(local(COLOMBO, "2026-11-18T09:00"), at).toHours();
        assertThat(who(r, at)).isEqualTo(List.of("a", "b", "c").get((int) (hours % 3)));
    }
}
