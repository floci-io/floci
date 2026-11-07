package io.floci.aws.services.cloudfront;

import io.floci.aws.config.SelfSignedTlsProfile;
import io.floci.aws.services.cloudfront.model.Distribution;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static io.floci.aws.services.cloudfront.CloudFrontWebFunctionOriginServingTest.createDistribution;
import static io.floci.aws.services.cloudfront.CloudFrontWebFunctionOriginServingTest.createFunction;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;

/** A viewer over TLS reaches the HTTPS port, while Floci fetches its own endpoint origin over plain HTTP. */
@QuarkusTest
@TestProfile(SelfSignedTlsProfile.class)
class CloudFrontWebFunctionOriginTlsIntegrationTest {

    @ConfigProperty(name = "quarkus.http.test-ssl-port", defaultValue = "0")
    int sslPort;

    @Inject
    CloudFrontService cloudFrontService;

    @Inject
    S3Service s3Service;

    @Inject
    IamService iamService;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void aViewerOverTlsReachesAnEndpointOrigin() {
        String domain = createFunction(s3Service, iamService, cleanup, "cf-tls-" + Long.toString(System.nanoTime(), 36), "IamAuth");
        Distribution created = createDistribution(cloudFrontService, cleanup, domain,
                CloudFrontService.MANAGED_ALL_VIEWER_EXCEPT_HOST_HEADER_ORIGIN_REQUEST_POLICY_ID);

        given().relaxedHTTPSValidation().baseUri("https://localhost").port(sslPort)
                .header("Host", created.getDomainName())
                .when().get("/")
                .then().statusCode(403)
                .header("lambda-web-request-id", matchesPattern("[A-Z2-7]{26}"))
                .body(containsString("You are not authorized to access this endpoint."));
    }
}
