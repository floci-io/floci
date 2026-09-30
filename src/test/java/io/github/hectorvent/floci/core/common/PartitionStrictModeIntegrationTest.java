package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.testing.PartitionMatrix;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.TreeSet;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code floci.partitions.strict=true}: a request signed for a service AWS does not publish in
 * the request's partition is refused the way the SDKs experience it on AWS, where the host
 * never resolves. Everything the partition does publish keeps working.
 */
@QuarkusTest
@TestProfile(PartitionStrictModeIntegrationTest.StrictPartitionsProfile.class)
class PartitionStrictModeIntegrationTest {

    @Inject
    ResolvedServiceCatalog catalog;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void cloudFrontIsRefusedInGovCloud() {
        given()
            .header("Authorization", PartitionMatrix.sigV4Auth("us-gov-west-1", "cloudfront"))
        .when().get("/2020-05-31/distribution").then().statusCode(404)
            .header("X-Amzn-Errortype", "UnknownOperationException")
            .body("__type", equalTo("UnknownOperationException"))
            .body("message", containsString("cloudfront has no endpoint in partition aws-us-gov"));
    }

    @Test
    void iamIsRefusedInEusc() {
        given()
            .header("Authorization", PartitionMatrix.sigV4Auth("eusc-de-east-1", "iam"))
            .formParam("Action", "ListRoles")
            .formParam("Version", "2010-05-08")
        .when().post("/").then().statusCode(404)
            .body("message", containsString("iam has no endpoint in partition aws-eusc"));
    }

    @Test
    void publishedServicesStillAnswer() {
        given()
            .header("Authorization", PartitionMatrix.sigV4Auth("cn-north-1", "s3"))
        .when().get("/").then().statusCode(200);
        given()
            .header("Authorization", PartitionMatrix.sigV4Auth("cn-north-1", "iam"))
            .formParam("Action", "ListRoles")
            .formParam("Version", "2010-05-08")
        .when().post("/").then().statusCode(200);
        given()
            .header("Authorization", PartitionMatrix.sigV4Auth("us-east-1", "cloudfront"))
        .when().get("/2020-05-31/distribution").then().statusCode(200);
    }

    /** A service the published data lists nowhere (FIS ships only an endpoint ruleset) is served everywhere. */
    @Test
    void servicesThePublishedDataDoesNotListAreServedEverywhere() {
        given()
            .header("Authorization", PartitionMatrix.sigV4Auth("us-gov-west-1", "fis"))
        .when().get("/experimentTemplates").then().statusCode(200);
    }

    /**
     * Strict mode can only refuse what AWS refuses: every signing name Floci answers to that
     * the published data knows at all must be one the commercial partition publishes, directly
     * or through the endpoint prefixes the signing name covers, or strict mode would 404 a
     * route that works.
     */
    @Test
    void everyCatalogCredentialScopeThePublishedDataKnowsIsInTheCommercialPartition() {
        TreeSet<String> unpublished = new TreeSet<>();
        for (ServiceDescriptor descriptor : catalog.all()) {
            for (String scope : descriptor.credentialScopes()) {
                if (AwsPartitions.publishesSomewhere(scope) && !AwsPartitions.commercial().offersSigningName(scope)) {
                    unpublished.add(descriptor.externalKey() + ":" + scope);
                }
            }
        }
        assertTrue(unpublished.isEmpty(),
                "credential scopes the commercial partition does not publish: " + unpublished);
    }

    public static final class StrictPartitionsProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.partitions.strict", "true");
        }
    }
}
