package io.floci.aws.services.athena;

import io.floci.aws.services.floci.duck.FlociDuckClient;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.testing.SidecarContainersProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A query that returns rows reaches the real floci-duck sidecar wrapped as {@code COPY (<query>) TO '<output>' ...},
 * where a trailing semicolon or line comment left in the query breaks the statement. Athena runs such a query, so the
 * form Floci gives the engine must survive the wrapping.
 */
@QuarkusTest
@TestProfile(SidecarContainersProfile.class)
class AthenaStatementEndSidecarIntegrationTest {

    @Inject
    FlociDuckClient duck;

    @Inject
    S3Service s3Service;

    @BeforeAll
    static void requireDocker() {
        SidecarContainersProfile.requireDocker();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SELECT 1 AS a;", "SELECT 1 AS a ;;\n", ";SELECT 1 AS a", "SELECT 1 AS a -- done",
            "SELECT 1 AS a -- done\n", "SELECT 1 AS a /* done */;", "SELECT 1 AS a -- done\n;", "SELECT 1 AS a -- done\r"})
    void aQueryEndingInASemicolonOrACommentWritesItsResult(String submitted) {
        try (AthenaSidecarFixture fixture = new AthenaSidecarFixture(duck, s3Service, "statementend")) {
            String key = "results/out.csv";
            fixture.wrote(key);

            // As AthenaService gives it to the engine: the recorded form, its last line ended.
            duck.execute(TrinoSqlCompatibility.recordedForm(submitted) + "\n", null,
                    "s3://" + fixture.bucket() + "/" + key);

            assertEquals("a\n1\n", new String(s3Service.getObject(fixture.bucket(), key).getData(),
                    StandardCharsets.UTF_8));
        }
    }
}
