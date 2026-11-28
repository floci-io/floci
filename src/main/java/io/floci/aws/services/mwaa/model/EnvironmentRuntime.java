package io.floci.aws.services.mwaa.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/**
 * Floci's own record of the secrets an environment's metadata database was set up with, never sent on the wire.
 * Floci starts the containers again with them after a restart, on the volumes they kept, so the database accepts its
 * password, the connections and variables Airflow encrypted still decrypt, and the admin still signs in. Stored beside
 * the environment, under its key, for the environment created at {@link #getEnvironmentCreatedAt()}.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class EnvironmentRuntime {

    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant environmentCreatedAt;
    private String dbPassword;
    private String fernetKey;
    private String airflowAdminPassword;

    public EnvironmentRuntime() {}

    /** The secrets the environment's containers run with now. */
    public static EnvironmentRuntime of(Environment environment) {
        EnvironmentRuntime runtime = new EnvironmentRuntime();
        runtime.setEnvironmentCreatedAt(environment.getCreatedAt());
        runtime.setDbPassword(environment.getDbPassword());
        runtime.setFernetKey(environment.getFernetKey());
        runtime.setAirflowAdminPassword(environment.getAirflowAdminPassword());
        return runtime;
    }

    public Instant getEnvironmentCreatedAt() { return environmentCreatedAt; }
    public void setEnvironmentCreatedAt(Instant environmentCreatedAt) { this.environmentCreatedAt = environmentCreatedAt; }

    public String getDbPassword() { return dbPassword; }
    public void setDbPassword(String dbPassword) { this.dbPassword = dbPassword; }

    public String getFernetKey() { return fernetKey; }
    public void setFernetKey(String fernetKey) { this.fernetKey = fernetKey; }

    public String getAirflowAdminPassword() { return airflowAdminPassword; }
    public void setAirflowAdminPassword(String airflowAdminPassword) { this.airflowAdminPassword = airflowAdminPassword; }
}
