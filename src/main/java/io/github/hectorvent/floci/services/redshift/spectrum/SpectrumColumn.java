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
    }
}
