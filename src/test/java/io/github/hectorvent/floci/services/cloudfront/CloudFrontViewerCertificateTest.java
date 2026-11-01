package io.github.hectorvent.floci.services.cloudfront;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.acm.AcmService;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.Certificate;
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
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A distribution's {@code ViewerCertificate.IAMCertificateId} is resolved against IAM and its
 * {@code ACMCertificateArn} against ACM, and the certificate it names cannot be deleted while a
 * distribution still holds it.
 */
@QuarkusTest
class CloudFrontViewerCertificateTest {

    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request";
    private static final String ACM_REGION = "us-east-1";
    private static final CertificateGenerator GENERATOR = new CertificateGenerator();

    private final CloudFrontService cloudFrontService;
    private final IamService iamService;
    private final AcmService acmService;

    CloudFrontViewerCertificateTest(CloudFrontService cloudFrontService, IamService iamService,
                                    AcmService acmService) {
        this.cloudFrontService = cloudFrontService;
        this.iamService = iamService;
        this.acmService = acmService;
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

    private Certificate importAcmCertificate() {
        CertificateGenerator.GeneratedCertificate pair = GENERATOR.generateSelfSignedCertificate(
                "cf.certs.test.local", List.of(), KeyAlgorithm.EC_prime256v1);
        return acmService.importCertificate(pair.certificatePem(), pair.privateKeyPem(), null, null,
                Map.of(), ACM_REGION);
    }

    private Distribution distributionWithCertificate(String certificateId) {
        return distributionWithViewerCertificate("IAMCertificateId", certificateId);
    }

    private Distribution distributionWithViewerCertificate(String member, String certificate) {
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
        viewerCertificate.put(member, certificate);
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

    @Test
    void createDistributionRejectsAnAcmCertificateThatDoesNotExist() {
        String missing = "arn:aws:acm:us-east-1:000000000000:certificate/" + UUID.randomUUID();

        AwsException rejected = assertThrows(AwsException.class, () -> cloudFrontService.createDistribution(
                distributionWithViewerCertificate("ACMCertificateArn", missing), Map.of()));

        assertEquals("InvalidViewerCertificate", rejected.getErrorCode());
        assertEquals(400, rejected.getHttpStatus());
    }

    @Test
    void createDistributionRejectsAnIamCertificateArnAsAnAcmCertificate() {
        ServerCertificate certificate = uploadCertificate("cf-cert-as-acm-" + suffix());

        AwsException rejected = assertThrows(AwsException.class, () -> cloudFrontService.createDistribution(
                distributionWithViewerCertificate("ACMCertificateArn", certificate.getArn()), Map.of()));

        assertEquals("InvalidViewerCertificate", rejected.getErrorCode());
        assertEquals(400, rejected.getHttpStatus());
    }

    @Test
    void anAcmCertificateIsHeldByTheDistributionUntilItIsGone() {
        String certificateArn = importAcmCertificate().getArn();

        Distribution created = cloudFrontService.createDistribution(
                distributionWithViewerCertificate("ACMCertificateArn", certificateArn), Map.of());

        assertEquals(List.of(created.getArn()), acmService.certificateInUseBy(certificateArn, ACM_REGION));
        AwsException refused = assertThrows(AwsException.class,
                () -> acmService.deleteCertificate(certificateArn, ACM_REGION));
        assertEquals("ResourceInUseException", refused.getErrorCode());
        assertEquals(400, refused.getHttpStatus());

        cloudFrontService.removeDistribution(created.getId());

        acmService.deleteCertificate(certificateArn, ACM_REGION);
    }

    @Test
    void replacingTheAcmCertificateReleasesTheOldOneAndHoldsTheNewOne() {
        String oldArn = importAcmCertificate().getArn();
        String newArn = importAcmCertificate().getArn();
        Distribution created = cloudFrontService.createDistribution(
                distributionWithViewerCertificate("ACMCertificateArn", oldArn), Map.of());

        cloudFrontService.updateDistribution(created.getId(), created.getEtag(),
                distributionWithViewerCertificate("ACMCertificateArn", newArn));

        acmService.deleteCertificate(oldArn, ACM_REGION);
        assertEquals(List.of(created.getArn()), acmService.certificateInUseBy(newArn, ACM_REGION));

        cloudFrontService.removeDistribution(created.getId());
        acmService.deleteCertificate(newArn, ACM_REGION);
    }
}
