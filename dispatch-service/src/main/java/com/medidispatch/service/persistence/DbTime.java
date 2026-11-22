package com.medidispatch.service.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * Converts between the engine's {@link Instant} and Postgres {@code timestamptz}.
 *
 * <p>The Postgres driver reads and writes {@code timestamptz} as {@link OffsetDateTime}, not {@code Instant}, so
 * every adapter goes through here. {@code null} stays {@code null} ("never" in the domain). Postgres keeps
 * microseconds and would ROUND anything finer (possibly up, into the future); this truncates to the microsecond
 * first, so a stored time is never later than the moment it describes.
 */
final class DbTime {

    private DbTime() {
    }

    /**
     * @param instant a moment, or {@code null}
     * @return the same moment at UTC, ready to bind as a parameter; {@code null} if the input is {@code null}
     */
    static OffsetDateTime toDb(Instant instant) {
        if (instant == null) {
            return null;
        }
        return instant.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }

    /**
     * @param rs     the current row
     * @param column a {@code timestamptz} column
     * @return the moment stored there, or {@code null} if the column is NULL
     * @throws SQLException if the column cannot be read
     */
    static Instant fromDb(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        if (value == null) {
            return null;
        }
        return value.toInstant();
    }
}
