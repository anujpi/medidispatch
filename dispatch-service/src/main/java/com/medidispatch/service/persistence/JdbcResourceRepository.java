package com.medidispatch.service.persistence;

import com.medidispatch.core.domain.Location;
import com.medidispatch.core.domain.Resource;
import com.medidispatch.core.domain.ResourceStatus;
import com.medidispatch.core.domain.ResourceType;
import com.medidispatch.core.port.ResourceRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * {@link ResourceRepository} on Postgres: table {@code units}, positions as PostGIS {@code geography} points with a
 * spatial (GiST) index for the "units near X" searches.
 *
 * <p>Column ownership: this adapter never writes {@code claimed_by}; that column belongs to
 * {@link JdbcResourceClaimer}. Saving a unit therefore never undoes a claim.
 *
 * <p>Distances use a sphere ({@code false} = "not the ellipsoid" in {@code ST_DWithin} / {@code ST_Distance}), the
 * same maths as {@link Location#distanceKmTo}, so this adapter and the in-memory one agree on who is in range and
 * in what order.
 */
public class JdbcResourceRepository implements ResourceRepository {

    /** Every column a {@link Resource} needs, positions split back into latitude (ST_Y) and longitude (ST_X). */
    private static final String COLUMNS = """
            id, type, status, last_seen_at, last_moved_at,
            ST_Y(position::geometry) AS lat, ST_X(position::geometry) AS lon,
            ST_Y(moved_from::geometry) AS from_lat, ST_X(moved_from::geometry) AS from_lon
            """;

    private final JdbcClient jdbc;

    /**
     * @param jdbc Spring's fluent JDBC API over the application's DataSource
     */
    public JdbcResourceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Stores the unit, or updates the stored one with the same id (upsert). Every column is written except
     * {@code claimed_by}.
     *
     * @param r the unit to store
     * @throws IllegalArgumentException if {@code r} is null
     */
    @Override
    public void save(Resource r) {
        if (r == null) {
            throw new IllegalArgumentException("resource is required");
        }
        jdbc.sql("""
                INSERT INTO units (id, type, status, position, moved_from, last_seen_at, last_moved_at)
                VALUES (:id, :type, :status,
                        ST_Point(:lon, :lat, 4326)::geography, ST_Point(:fromLon, :fromLat, 4326)::geography,
                        :seen, :moved)
                ON CONFLICT (id) DO UPDATE SET
                    type = excluded.type, status = excluded.status, position = excluded.position,
                    moved_from = excluded.moved_from, last_seen_at = excluded.last_seen_at,
                    last_moved_at = excluded.last_moved_at
                """)
                .param("id", r.id())
                .param("type", r.type().name())
                .param("status", r.status().name())
                .param("lon", r.location().longitude())
                .param("lat", r.location().latitude())
                .param("fromLon", r.movedFrom().longitude())
                .param("fromLat", r.movedFrom().latitude())
                // the type is named because a null value alone does not tell the driver what it is
                .param("seen", DbTime.toDb(r.lastSeenAt().orElse(null)), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("moved", DbTime.toDb(r.lastMovedAt().orElse(null)), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    /**
     * @param id the unit id
     * @return the unit, or empty if there is none with that id
     * @throws IllegalArgumentException if {@code id} is null
     */
    @Override
    public Optional<Resource> findById(String id) {
        if (id == null) {
            throw new IllegalArgumentException("id is required");
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM units WHERE id = :id")
                .param("id", id)
                .query((rs, rowNum) -> toResource(rs))
                .optional();
    }

    /**
     * Available units of the requested types within the radius (edge included), nearest first, then by id.
     *
     * @param center   where to search from
     * @param radiusKm how far to look, in km; must be positive
     * @param types    which kinds of unit are wanted; an empty set finds nothing
     * @return the matching units
     * @throws IllegalArgumentException if an argument is missing or the radius is not positive
     */
    @Override
    public List<Resource> findAvailableNear(Location center, double radiusKm, Set<ResourceType> types) {
        return findNear(ResourceStatus.AVAILABLE, center, radiusKm, types);
    }

    /**
     * Same as {@link #findAvailableNear}, but for units that are already on a job (for preemption).
     *
     * @param center   where to search from
     * @param radiusKm how far to look, in km; must be positive
     * @param types    which kinds of unit are wanted; an empty set finds nothing
     * @return the matching units, nearest first, then by id
     * @throws IllegalArgumentException if an argument is missing or the radius is not positive
     */
    @Override
    public List<Resource> findAssignedNear(Location center, double radiusKm, Set<ResourceType> types) {
        return findNear(ResourceStatus.ASSIGNED, center, radiusKm, types);
    }

    /**
     * @return every unit whatever its status, sorted by id
     */
    @Override
    public List<Resource> findAll() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM units ORDER BY id")
                .query((rs, rowNum) -> toResource(rs))
                .list();
    }

    // ST_DWithin picks the candidates using the GiST index (only the boxes near the center are opened); only
    // those few rows are then sorted by distance.
    private List<Resource> findNear(ResourceStatus status, Location center, double radiusKm, Set<ResourceType> types) {
        if (center == null) {
            throw new IllegalArgumentException("center is required");
        }
        if (types == null) {
            throw new IllegalArgumentException("types is required");
        }
        if (radiusKm <= 0) {
            throw new IllegalArgumentException("radiusKm must be positive, got " + radiusKm);
        }
        // A list parameter expands to IN (?, ?, ...), one ? per element; an empty list would be "IN ()", which is
        // not valid SQL.
        if (types.isEmpty()) {
            return List.of();
        }
        List<String> typeNames = new ArrayList<>();
        for (ResourceType t : types) {
            typeNames.add(t.name());
        }
        return jdbc.sql("SELECT " + COLUMNS + """
                 FROM units
                WHERE status = :status AND type IN (:types)
                  AND ST_DWithin(position, ST_Point(:lon, :lat, 4326)::geography, :metres, false)
                ORDER BY ST_Distance(position, ST_Point(:lon, :lat, 4326)::geography, false), id
                """)
                .param("status", status.name())
                .param("types", typeNames)
                .param("lon", center.longitude())
                .param("lat", center.latitude())
                .param("metres", radiusKm * 1000)
                .query((rs, rowNum) -> toResource(rs))
                .list();
    }

    /** Turns the current row (selected with {@link #COLUMNS}) back into a unit. */
    private static Resource toResource(ResultSet rs) throws SQLException {
        return Resource.restore(
                rs.getString("id"),
                ResourceType.valueOf(rs.getString("type")),
                ResourceStatus.valueOf(rs.getString("status")),
                new Location(rs.getDouble("lat"), rs.getDouble("lon")),
                new Location(rs.getDouble("from_lat"), rs.getDouble("from_lon")),
                DbTime.fromDb(rs, "last_seen_at"),
                DbTime.fromDb(rs, "last_moved_at"));
    }
}
