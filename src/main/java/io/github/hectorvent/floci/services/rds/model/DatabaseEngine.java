package io.github.hectorvent.floci.services.rds.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Locale;

@RegisterForReflection
public enum DatabaseEngine {
    POSTGRES, AURORA_POSTGRESQL, MYSQL, AURORA_MYSQL, MARIADB;

    public int defaultPort() {
        return switch (this) {
            case POSTGRES, AURORA_POSTGRESQL -> 5432;
            case MYSQL, AURORA_MYSQL, MARIADB -> 3306;
        };
    }

    public String apiName() {
        return this.name().toLowerCase(Locale.ROOT).replace("_", "-");
    }
}
