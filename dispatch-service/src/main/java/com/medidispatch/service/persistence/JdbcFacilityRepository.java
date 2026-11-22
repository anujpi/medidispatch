package com.medidispatch.service.persistence;

import com.medidispatch.core.domain.Capability;
import com.medidispatch.core.domain.Facility;
import com.medidispatch.core.domain.FacilityKind;
import com.medidispatch.core.domain.Location;
import com.medidispatch.core.port.FacilityRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * {@link FacilityRepository} on Postgres: the {@code facilities} table. Capabilities are a {@code text[]} column; the
 * driver turns a Java {@code String[]} into one and back.
 */
public class JdbcFacilityRepository implements FacilityRepository {

    private static final String COLUMNS = """
            id, name, kind, capabilities, accepting, phone,
            ST_Y(location::geometry) AS lat, ST_X(location::geometry) AS lon
            """;

    private final JdbcClient jdbc;

    /**
     * @param jdbc Spring's fluent JDBC API over the application's DataSource
     */
    public JdbcFacilityRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Stores the facility, or replaces the stored one with the same id (upsert): this is how diversion is switched
     * on and off.
     *
     * @param facility the facility to store
     * @throws IllegalArgumentException if {@code facility} is null
     */
    @Override
    public void save(Facility facility) {
        if (facility == null) {
            throw new IllegalArgumentException("facility is required");
        }
        jdbc.sql("""
                INSERT INTO facilities (id, name, kind, location, capabilities, accepting, phone)
                VALUES (:id, :name, :kind, ST_Point(:lon, :lat, 4326)::geography, :capabilities, :accepting, :phone)
                ON CONFLICT (id) DO UPDATE SET
                    name = excluded.name, kind = excluded.kind, location = excluded.location,
                    capabilities = excluded.capabilities, accepting = excluded.accepting, phone = excluded.phone
                """)
                .param("id", facility.id())
                .param("name", facility.name())
                .param("kind", facility.kind().name())
                .param("lon", facility.location().longitude())
                .param("lat", facility.location().latitude())
                .param("capabilities", toNames(facility.capabilities()))
                .param("accepting", facility.accepting())
                .param("phone", facility.phone(), Types.VARCHAR)
                .update();
    }

    /**
     * @param id the facility id
     * @return the facility, or empty if there is none with that id
     * @throws IllegalArgumentException if {@code id} is null
     */
    @Override
    public Optional<Facility> findById(String id) {
        if (id == null) {
            throw new IllegalArgumentException("id is required");
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM facilities WHERE id = :id")
                .param("id", id)
                .query((rs, rowNum) -> toFacility(rs))
                .optional();
    }

    /**
     * @return every facility (hospitals and clinics), sorted by id
     */
    @Override
    public List<Facility> findAll() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM facilities ORDER BY id")
                .query((rs, rowNum) -> toFacility(rs))
                .list();
    }

    /**
     * @param capabilities a set of capabilities
     * @return their names as an array, which the driver sends as a {@code text[]}; always in the enum's order, so
     *         the stored array does not depend on the set's order
     */
    static String[] toNames(Set<Capability> capabilities) {
        Set<Capability> sorted = capabilities.isEmpty() ? EnumSet.noneOf(Capability.class) : EnumSet.copyOf(capabilities);
        String[] names = new String[sorted.size()];
        int i = 0;
        for (Capability c : sorted) {
            names[i] = c.name();
            i++;
        }
        return names;
    }

    /**
     * @param rs     the current row
     * @param column a {@code text[]} column of capability names
     * @return the capabilities it holds
     */
    static Set<Capability> fromNames(ResultSet rs, String column) throws SQLException {
        String[] names = (String[]) rs.getArray(column).getArray();
        Set<Capability> result = EnumSet.noneOf(Capability.class);
        for (String name : names) {
            result.add(Capability.valueOf(name));
        }
        return result;
    }

    /** Turns the current row (selected with {@link #COLUMNS}) back into a facility. */
    private static Facility toFacility(ResultSet rs) throws SQLException {
        return new Facility(
                rs.getString("id"),
                rs.getString("name"),
                new Location(rs.getDouble("lat"), rs.getDouble("lon")),
                FacilityKind.valueOf(rs.getString("kind")),
                fromNames(rs, "capabilities"),
                rs.getBoolean("accepting"),
                rs.getString("phone"));
    }
}
