package io.floci.aws.services.emr;

import io.floci.aws.testing.RdsMockProfile;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.floci.aws.testing.EmrRequests.emr;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * A default release label the catalogue does not describe is still a known release: it is listed
 * (newest first here) and described with no applications, so Floci never calls its own default
 * unknown.
 *
 * <p>The default release label, {@code emr-7.9.0}, comes from {@link RdsMockProfile}.
 */
@QuarkusTest
@TestProfile(RdsMockProfile.class)
class EmrReleaseCatalogCustomDefaultIntegrationTest {

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void theConfiguredDefaultIsListedAndDescribed() {
        emr("ListReleaseLabels", "{}").then().statusCode(200)
                .body("ReleaseLabels[0]", equalTo("emr-7.9.0"));
        emr("DescribeReleaseLabel", "{\"ReleaseLabel\":\"emr-7.9.0\"}").then().statusCode(200)
                .body("ReleaseLabel", equalTo("emr-7.9.0"))
                .body("Applications", hasSize(0));
    }
}
