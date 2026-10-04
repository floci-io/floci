package io.github.hectorvent.floci.services.cloudfront;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.github.hectorvent.floci.services.cloudfront.model.DefaultCacheBehavior;
import io.github.hectorvent.floci.services.cloudfront.model.Distribution;
import io.github.hectorvent.floci.services.cloudfront.model.DistributionConfig;
import io.github.hectorvent.floci.services.cloudfront.model.Origin;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.ServerCertificate;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A distribution's {@code ViewerCertificate.IAMCertificateId} is resolved against IAM, and the
 * certificate it names cannot be deleted while a distribution still holds it.
 */
@QuarkusTest
class CloudFrontViewerCertificateTest {

    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request";
    private static final CertificateGenerator GENERATOR = new CertificateGenerator();

    private final CloudFrontService cloudFrontService;
    private final IamService iamService;

    CloudFrontViewerCertificateTest(CloudFrontService cloudFrontService, IamService iamService) {
        this.cloudFrontService = cloudFrontService;
        this.iamService = iamService;
    }

    private static String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }

    private ServerCertificate uploadCertificate(String name) {
        CertificateGenerator.GeneratedCertificate pair = GENERATOR.generateSelfSignedCertificate(
                "cf.certs.test.local", List.of(), KeyAlgorithm.EC_prime256v1);
        return iamService.uploadServerCertificate(name, "/cloudfront/", pair.certificatePem(),
                pair.privateKeyPem(), null, Map.of());
    }

    private Distribution distributionWithCertificate(String certificateId) {
        Origin origin = new Origin();
        origin.setId("cert-origin");
        origin.setDomainName("example.com");
        Map<String, Object> customOriginConfig = new LinkedHashMap<>();
        customOriginConfig.put("HTTPPort", "80");
        customOriginConfig.put("HTTPSPort", "443");
        customOriginConfig.put("OriginProtocolPolicy", "http-only");
        origin.setCustomOriginConfig(customOriginConfig);

        DefaultCacheBehavior behavior = new DefaultCacheBehavior();
        behavior.setTargetOriginId("cert-origin");
        behavior.setViewerProtocolPolicy("allow-all");

        DistributionConfig config = new DistributionConfig();
        config.setEnabled(false);
        config.setComment("viewer-certificate-test");
        config.setOrigins(List.of(origin));
        config.setDefaultCacheBehavior(behavior);
        Map<String, String> viewerCertificate = new LinkedHashMap<>();
        viewerCertificate.put("IAMCertificateId", certificateId);
        viewerCertificate.put("SSLSupportMethod", "sni-only");
        config.setViewerCertificate(viewerCertificate);

        Distribution distribution = new Distribution();
        distribution.setConfig(config);
        return distribution;
    }

    @Test
    void createDistributionRejectsAnIamCertificateIdThatDoesNotExist() {
        AwsException rejected = assertThrows(AwsException.class,
                () -> cloudFrontService.createDistribution(distributionWithCertificate("ASCAMISSING"), Map.of()));

        assertEquals("InvalidViewerCertificate", rejected.getErrorCode());
        assertEquals(400, rejected.getHttpStatus());
    }

    @Test
    void createDistributionAcceptsAnUploadedCertificateAndHoldsItUntilTheDistributionIsGone() {
        String name = "cf-cert-" + suffix();
        ServerCertificate certificate = uploadCertificate(name);

        Distribution created = cloudFrontService.createDistribution(
                distributionWithCertificate(certificate.getServerCertificateId()), Map.of());

        given().header("Authorization", IAM_AUTH)
                .contentType(ContentType.URLENC)
                .formParam("Action", "DeleteServerCertificate")
                .formParam("ServerCertificateName", name)
            .when().post("/").then()
                .statusCode(409)
                .body("ErrorResponse.Error.Code", equalTo("DeleteConflict"))
                .body("ErrorResponse.Error.Message", containsString(created.getId()));

        cloudFrontService.removeDistribution(created.getId());

        given().header("Authorization", IAM_AUTH)
                .contentType(ContentType.URLENC)
                .formParam("Action", "DeleteServerCertificate")
                .formParam("ServerCertificateName", name)
            .when().post("/").then().statusCode(200);
    }
}
