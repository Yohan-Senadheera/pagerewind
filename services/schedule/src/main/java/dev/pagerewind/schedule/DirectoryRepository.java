package dev.pagerewind.schedule;

import dev.pagerewind.events.Contact;
import dev.pagerewind.messaging.Db;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** People, teams, rotations, overrides and escalation policies. */
@Repository
public class DirectoryRepository {

    public record Person(String id, String name, List<Contact> contacts) {
    }

    public record Team(String id, String name, int policyRepeat, boolean defaultTeam) {
    }

    private final JdbcClient jdbc;

    public DirectoryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---- people

    public void savePerson(String id, String name, List<Contact> contacts) {
        jdbc.sql("INSERT INTO person (id, name) VALUES (:id, :name) ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name")
                .param("id", id).param("name", name).update();
        jdbc.sql("DELETE FROM contact WHERE person_id = :id").param("id", id).update();
        for (Contact contact : contacts) {
            jdbc.sql("INSERT INTO contact (person_id, channel, address) VALUES (:id, :channel, :address) ON CONFLICT DO NOTHING")
                    .param("id", id).param("channel", contact.channel().name()).param("address", contact.address()).update();
        }
    }

    public Optional<Person> findPerson(String id) {
        return jdbc.sql("SELECT id, name FROM person WHERE id = :id").param("id", id)
                .query((rs, row) -> new Person(rs.getString("id"), rs.getString("name"), contacts(rs.getString("id"))))
                .optional();
    }

    public List<Person> findPeople() {
        return jdbc.sql("SELECT id, name FROM person ORDER BY id")
                .query((rs, row) -> new Person(rs.getString("id"), rs.getString("name"), contacts(rs.getString("id"))))
                .list();
    }

    public boolean personExists(String id) {
        return jdbc.sql("SELECT count(*) FROM person WHERE id = :id").param("id", id).query(Long.class).single() > 0;
    }

    private List<Contact> contacts(String personId) {
        return jdbc.sql("SELECT channel, address FROM contact WHERE person_id = :id ORDER BY channel, address")
                .param("id", personId)
                .query((rs, row) -> new Contact(Contact.Channel.valueOf(rs.getString("channel")), rs.getString("address")))
                .list();
    }

    // ---- teams and routing

    public void saveTeam(String id, String name, boolean defaultTeam, List<String> services) {
        if (defaultTeam) {
            jdbc.sql("UPDATE team SET is_default = false WHERE is_default AND id <> :id").param("id", id).update();
        }
        jdbc.sql("""
                        INSERT INTO team (id, name, is_default) VALUES (:id, :name, :isDefault)
                        ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, is_default = EXCLUDED.is_default""")
                .param("id", id).param("name", name).param("isDefault", defaultTeam).update();
        jdbc.sql("DELETE FROM team_service WHERE team_id = :id").param("id", id).update();
        for (String service : services) {
            jdbc.sql("""
                            INSERT INTO team_service (service, team_id) VALUES (:service, :id)
                            ON CONFLICT (service) DO UPDATE SET team_id = EXCLUDED.team_id""")
                    .param("service", service).param("id", id).update();
        }
    }

    public Optional<Team> findTeam(String id) {
        return jdbc.sql("SELECT id, name, policy_repeat, is_default FROM team WHERE id = :id").param("id", id)
                .query((rs, row) -> new Team(rs.getString("id"), rs.getString("name"), rs.getInt("policy_repeat"),
                        rs.getBoolean("is_default")))
                .optional();
    }

    public List<Team> findTeams() {
        return jdbc.sql("SELECT id, name, policy_repeat, is_default FROM team ORDER BY id")
                .query((rs, row) -> new Team(rs.getString("id"), rs.getString("name"), rs.getInt("policy_repeat"),
                        rs.getBoolean("is_default")))
                .list();
    }

    public List<String> servicesOf(String teamId) {
        return jdbc.sql("SELECT service FROM team_service WHERE team_id = :id ORDER BY service")
                .param("id", teamId).query(String.class).list();
    }

    /** The team that owns a service, or the default team if no team claims it. */
    public Optional<String> teamForService(String service) {
        Optional<String> owner = jdbc.sql("SELECT team_id FROM team_service WHERE service = :service")
                .param("service", service).query(String.class).optional();
        if (owner.isPresent()) {
            return owner;
        }
        return jdbc.sql("SELECT id FROM team WHERE is_default").query(String.class).optional();
    }

    // ---- rotations

    public void saveRotation(Rotation rotation) {
        jdbc.sql("""
                        INSERT INTO rotation (id, team_id, name, start_local, time_zone, shift_hours)
                        VALUES (:id, :teamId, :name, :start, :zone, :hours)
                        ON CONFLICT (id) DO UPDATE SET team_id = EXCLUDED.team_id, name = EXCLUDED.name,
                            start_local = EXCLUDED.start_local, time_zone = EXCLUDED.time_zone,
                            shift_hours = EXCLUDED.shift_hours""")
                .param("id", rotation.id()).param("teamId", rotation.teamId()).param("name", rotation.name())
                .param("start", rotation.start()).param("zone", rotation.zone().getId())
                .param("hours", rotation.shiftHours()).update();
        jdbc.sql("DELETE FROM rotation_member WHERE rotation_id = :id").param("id", rotation.id()).update();
        for (int i = 0; i < rotation.members().size(); i++) {
            jdbc.sql("INSERT INTO rotation_member (rotation_id, position, person_id) VALUES (:id, :pos, :person)")
                    .param("id", rotation.id()).param("pos", i).param("person", rotation.members().get(i)).update();
        }
    }

    public Optional<Rotation> findRotation(String id) {
        return jdbc.sql("SELECT id, team_id, name, start_local, time_zone, shift_hours FROM rotation WHERE id = :id")
                .param("id", id)
                .query((rs, row) -> new Rotation(rs.getString("id"), rs.getString("team_id"), rs.getString("name"),
                        members(rs.getString("id")), rs.getObject("start_local", LocalDateTime.class),
                        ZoneId.of(rs.getString("time_zone")), rs.getInt("shift_hours")))
                .optional();
    }

    public List<Rotation> rotationsOf(String teamId) {
        List<String> ids = jdbc.sql("SELECT id FROM rotation WHERE team_id = :team ORDER BY id")
                .param("team", teamId).query(String.class).list();
        List<Rotation> rotations = new ArrayList<>();
        for (String id : ids) {
            findRotation(id).ifPresent(rotations::add);
        }
        return rotations;
    }

    private List<String> members(String rotationId) {
        return jdbc.sql("SELECT person_id FROM rotation_member WHERE rotation_id = :id ORDER BY position")
                .param("id", rotationId).query(String.class).list();
    }

    public long addOverride(String rotationId, String personId, Instant start, Instant end) {
        return jdbc.sql("""
                        INSERT INTO rotation_override (rotation_id, person_id, start_at, end_at)
                        VALUES (:rotation, :person, :start, :end) RETURNING id""")
                .param("rotation", rotationId).param("person", personId)
                .param("start", Db.toDb(start)).param("end", Db.toDb(end))
                .query(Long.class).single();
    }

    /** Overrides that have not ended by {@code after}. */
    public List<RotationOverride> overrides(String rotationId, Instant after) {
        return jdbc.sql("""
                        SELECT id, rotation_id, person_id, start_at, end_at FROM rotation_override
                         WHERE rotation_id = :rotation AND end_at > :after ORDER BY start_at, id""")
                .param("rotation", rotationId).param("after", Db.toDb(after))
                .query((rs, row) -> new RotationOverride(rs.getLong("id"), rs.getString("rotation_id"),
                        rs.getString("person_id"), Db.instant(rs, "start_at"), Db.instant(rs, "end_at")))
                .list();
    }

    // ---- escalation policy

    public void savePolicy(String teamId, int repeat, List<EscalationStep> steps) {
        jdbc.sql("UPDATE team SET policy_repeat = :repeat WHERE id = :id").param("repeat", repeat).param("id", teamId).update();
        jdbc.sql("DELETE FROM escalation_step WHERE team_id = :id").param("id", teamId).update();
        for (EscalationStep step : steps) {
            jdbc.sql("""
                            INSERT INTO escalation_step (team_id, step_no, escalate_after_minutes, target_type, target_id)
                            VALUES (:team, :no, :after, :type, :target)""")
                    .param("team", teamId).param("no", step.stepNo()).param("after", step.escalateAfterMinutes())
                    .param("type", step.targetType().name()).param("target", step.targetId()).update();
        }
    }

    public List<EscalationStep> steps(String teamId) {
        return jdbc.sql("""
                        SELECT step_no, escalate_after_minutes, target_type, target_id
                          FROM escalation_step WHERE team_id = :team ORDER BY step_no""")
                .param("team", teamId)
                .query((rs, row) -> new EscalationStep(rs.getInt("step_no"), rs.getInt("escalate_after_minutes"),
                        EscalationStep.TargetType.valueOf(rs.getString("target_type")), rs.getString("target_id")))
                .list();
    }
}
