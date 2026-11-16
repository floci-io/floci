package io.floci.aws.services.dsql;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.List;
import java.util.Map;

/** The external backend configured with a superuser password its server refuses. */
public final class DsqlExternalBackendWrongPasswordProfile implements QuarkusTestProfile {
    @Override
    public List<TestResourceEntry> testResources() {
        return List.of(new TestResourceEntry(ExternalPostgresResource.class,
                Map.of(ExternalPostgresResource.FLOCI_PASSWORD, "not-the-servers-password")));
    }
}
