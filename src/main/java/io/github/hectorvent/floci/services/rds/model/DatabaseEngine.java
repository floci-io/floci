package io.github.hectorvent.floci.services.rds.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public enum DatabaseEngine {
    POSTGRES, MYSQL, MARIADB;

    public int defaultPort() {
        return switch (this) {
            case POSTGRES -> 5432;
            case MYSQL, MARIADB -> 3306;
        };
    }

    // The API reference documents 16 characters for every engine, but RDS accepts longer
    // master usernames on PostgreSQL and MySQL. These are the limits the service enforces.
    public int maxMasterUsernameLength() {
        return switch (this) {
            case POSTGRES -> 63;
            case MYSQL -> 32;
            case MARIADB -> 16;
        };
    }
}
