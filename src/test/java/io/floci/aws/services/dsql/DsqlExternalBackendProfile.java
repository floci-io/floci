package io.floci.aws.services.dsql;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.List;

/** Clusters on one external PostgreSQL server, {@link ExternalPostgresResource}, instead of a container each. */
public final class DsqlExternalBackendProfile implements QuarkusTestProfile {
    @Override
    public List<TestResourceEntry> testResources() {
        return List.of(new TestResourceEntry(ExternalPostgresResource.class));
    }
}
