package io.github.hectorvent.floci.services.redshift.spectrum;

import java.util.Locale;

public record SpectrumColumn(String name, Type type) {

    public SpectrumColumn {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Spectrum column name is required");
        }
        if (type == null) {
            throw new IllegalArgumentException("Spectrum column type is required");
        }
        if (!name.equals(name.trim())) {
            throw new IllegalArgumentException("Spectrum column name must not contain surrounding whitespace");
        }
    }

    public enum Type {
        VARCHAR,
        CHAR,
        INTEGER,
        BIGINT,
        DECIMAL,
        BOOLEAN,
        DATE,
        TIMESTAMP;

        public static Type fromSql(String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Spectrum column type is required");
            }
            String normalized = value.trim().toUpperCase(Locale.ROOT);
            return switch (normalized) {
                case "VARCHAR" -> VARCHAR;
                case "CHAR" -> CHAR;
                case "INTEGER", "INT" -> INTEGER;
                case "BIGINT" -> BIGINT;
                case "DECIMAL", "NUMERIC" -> DECIMAL;
                case "BOOLEAN", "BOOL" -> BOOLEAN;
                case "DATE" -> DATE;
                case "TIMESTAMP" -> TIMESTAMP;
                default -> throw new IllegalArgumentException("Unsupported Spectrum column type: " + value);
            };
        }

        public String postgresType() {
            return switch (this) {
                case VARCHAR -> "VARCHAR";
                case CHAR -> "CHAR";
                case INTEGER -> "INTEGER";
                case BIGINT -> "BIGINT";
                case DECIMAL -> "DECIMAL";
                case BOOLEAN -> "BOOLEAN";
                case DATE -> "DATE";
                case TIMESTAMP -> "TIMESTAMP";
            };
        }

        /**
         * The well-known PostgreSQL {@code pg_type} OID for this column's Postgres type, needed to
         * synthesize a wire-accurate {@code RowDescription} for an Extended Query Describe response
         * without a backend round trip.
         */
        public int postgresTypeOid() {
            return switch (this) {
                case VARCHAR -> 1043;
                case CHAR -> 1042;
                case INTEGER -> 23;
                case BIGINT -> 20;
                case DECIMAL -> 1700;
                case BOOLEAN -> 16;
                case DATE -> 1082;
                case TIMESTAMP -> 1114;
            };
        }

        /** The fixed on-wire type length for this Postgres type, or {@code -1} for a variable-length type. */
        public short postgresTypeLength() {
            return switch (this) {
                case VARCHAR, CHAR, DECIMAL -> -1;
                case INTEGER, DATE -> 4;
                case BIGINT, TIMESTAMP -> 8;
                case BOOLEAN -> 1;
            };
        }
    }
}
