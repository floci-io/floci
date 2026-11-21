package io.floci.aws.services.cloudfront;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.acm.AcmService;
import io.floci.aws.services.acm.CertificateGenerator;
import io.floci.aws.services.acm.model.Certificate;
import io.floci.aws.services.acm.model.KeyAlgorithm;
import io.floci.aws.services.cloudfront.model.DefaultCacheBehavior;
import io.floci.aws.services.cloudfront.model.Distribution;
import io.floci.aws.services.cloudfront.model.DistributionConfig;
import io.floci.aws.services.cloudfront.model.Origin;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.model.ServerCertificate;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
        return uploadCertificate(name, "/cloudfront/");
    }

    private ServerCertificate uploadCertificate(String name, String path) {
        CertificateGenerator.GeneratedCertificate pair = GENERATOR.generateSelfSignedCertificate(
                "cf.certs.test.local", List.of(), KeyAlgorithm.EC_prime256v1);
        return iamService.uploadServerCertificate(name, path, pair.certificatePem(),
                pair.privateKeyPem(), null, Map.of());
    }

    private ValidatableResponse deleteServerCertificate(String name) {
        return given().header("Authorization", IAM_AUTH)
                .contentType(ContentType.URLENC)
                .formParam("Action", "DeleteServerCertificate")
                .formParam("ServerCertificateName", name)
            .when().post("/").then();
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
        return distributionWithViewerCertificate(completeViewerCertificate(member, certificate));
    }

    /**
     * A certificate named in the location member handed in, carrying the two members AWS requires
     * alongside one: a certificate named without them is refused.
     */
    private static Map<String, String> completeViewerCertificate(String member, String certificate) {
        Map<String, String> viewerCertificate = new LinkedHashMap<>();
        viewerCertificate.put(member, certificate);
        viewerCertificate.put("SSLSupportMethod", "sni-only");
        viewerCertificate.put("MinimumProtocolVersion", "TLSv1.2_2021");
        return viewerCertificate;
    }

    /**
     * A viewer certificate for a distribution on the CloudFront domain name, asking for the
     * security policy handed in, or for none when that is null.
     */
    private static Map<String, String> defaultViewerCertificate(String protocolVersion) {
        Map<String, String> viewerCertificate = new LinkedHashMap<>();
        viewerCertificate.put("CloudFrontDefaultCertificate", "true");
        if (protocolVersion != null) {
            viewerCertificate.put("MinimumProtocolVersion", protocolVersion);
        }
        return viewerCertificate;
    }

    private Distribution distributionWithViewerCertificate(Map<String, String> viewerCertificate) {
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

        deleteServerCertificate(name)
                .statusCode(409)
                .body("ErrorResponse.Error.Code", equalTo("DeleteConflict"))
                .body("ErrorResponse.Error.Message", containsString(created.getId()));

        cloudFrontService.removeDistribution(created.getId());

        deleteServerCertificate(name).statusCode(200);
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

    /**
     * A distribution on the CloudFront domain name is told to set
     * {@code CloudFrontDefaultCertificate} and leave the other fields empty, and an element left
     * empty reaches this protocol as a blank value. So a blank {@code IAMCertificateId} names no
     * certificate: the create is accepted rather than refused for an id nothing matches, and IAM
     * is told of no reference it would refuse a delete for.
     */
    @Test
    void aBlankIamCertificateIdNamesNoCertificate() {
        String body = """
                <?xml version="1.0" encoding="UTF-8"?>
                <DistributionConfig xmlns="http://cloudfront.amazonaws.com/doc/2020-05-31/">
                  <CallerReference>blank-iam-cert-%s</CallerReference>
                  <Origins>
                    <Quantity>1</Quantity>
                    <Items>
                      <Origin>
                        <Id>blank-cert-origin</Id>
                        <DomainName>example.com</DomainName>
                        <CustomOriginConfig>
                          <HTTPPort>80</HTTPPort>
                          <HTTPSPort>443</HTTPSPort>
                          <OriginProtocolPolicy>http-only</OriginProtocolPolicy>
                        </CustomOriginConfig>
                      </Origin>
                    </Items>
                  </Origins>
                  <DefaultCacheBehavior>
                    <TargetOriginId>blank-cert-origin</TargetOriginId>
                    <ViewerProtocolPolicy>allow-all</ViewerProtocolPolicy>
                  </DefaultCacheBehavior>
                  <ViewerCertificate>
                    <CloudFrontDefaultCertificate>true</CloudFrontDefaultCertificate>
                    <IAMCertificateId></IAMCertificateId>
                  </ViewerCertificate>
                  <Comment>blank-viewer-certificate</Comment>
                  <Enabled>false</Enabled>
                </DistributionConfig>
                """.formatted(suffix());

        String location = given()
                .contentType(ContentType.XML)
                .body(body)
            .when()
                .post("/2020-05-31/distribution")
            .then()
                .statusCode(201)
                .extract().header("Location");
        String id = location.substring(location.lastIndexOf('/') + 1);

        assertEquals(List.of(), cloudFrontService.serverCertificateReferences().stream()
                .filter(reference -> reference.referrer().contains(id))
                .toList());

        cloudFrontService.removeDistribution(id);
    }

    /**
     * The path a certificate was uploaded under does not decide whether a distribution can serve it
     * here, the id does. AWS documents {@code /cloudfront} for a certificate meant for CloudFront,
     * on IAM's upload and in CloudFront's own upload procedure, so what this pins is a deliberate
     * divergence rather than a recorded AWS answer: nothing published says the distribution write
     * refuses one stored elsewhere. Why it is left this way is under Server Certificates in
     * {@code docs/services/iam.md}.
     */
    @Test
    void aCertificateOutsideTheCloudFrontPathStillServesADistribution() {
        String name = "cf-cert-root-path-" + suffix();
        ServerCertificate certificate = uploadCertificate(name, "/");

        Distribution created = cloudFrontService.createDistribution(
                distributionWithCertificate(certificate.getServerCertificateId()), Map.of());

        deleteServerCertificate(name)
                .statusCode(409)
                .body("ErrorResponse.Error.Code", equalTo("DeleteConflict"))
                .body("ErrorResponse.Error.Message", containsString(created.getId()));

        cloudFrontService.removeDistribution(created.getId());

        deleteServerCertificate(name).statusCode(200);
    }

    /**
     * A certificate named in the viewer certificate "must also specify values for
     * MinimumProtocolVersion and SSLSupportMethod". The id here matches nothing, so the message
     * says which refusal the request got: the rule is read before the certificate is looked up.
     */
    @ParameterizedTest
    @ValueSource(strings = {"MinimumProtocolVersion", "SSLSupportMethod"})
    void createDistributionRefusesACertificateWithoutTheMembersItRequires(String missing) {
        Map<String, String> viewerCertificate =
                completeViewerCertificate("IAMCertificateId", "ASCAMISSING");
        viewerCertificate.remove(missing);

        AwsException rejected = assertThrows(AwsException.class, () -> cloudFrontService
                .createDistribution(distributionWithViewerCertificate(viewerCertificate), Map.of()));

        assertEquals("InvalidViewerCertificate", rejected.getErrorCode());
        assertEquals(400, rejected.getHttpStatus());
        assertEquals("You must specify values for MinimumProtocolVersion and SSLSupportMethod.",
                rejected.getMessage());
    }

    /**
     * An element left empty reaches this protocol as a blank value, and a blank is no value. The
     * certificate exists here, so what refuses the request is the rule rather than the lookup.
     */
    @ParameterizedTest
    @ValueSource(strings = {"MinimumProtocolVersion", "SSLSupportMethod"})
    void aBlankMemberIsNoValueAlongsideACertificate(String blanked) {
        String certificateArn = importAcmCertificate().getArn();
        Map<String, String> viewerCertificate =
                completeViewerCertificate("ACMCertificateArn", certificateArn);
        viewerCertificate.put(blanked, "");

        AwsException rejected = assertThrows(AwsException.class, () -> cloudFrontService
                .createDistribution(distributionWithViewerCertificate(viewerCertificate), Map.of()));

        assertEquals("InvalidViewerCertificate", rejected.getErrorCode());
        assertEquals("You must specify values for MinimumProtocolVersion and SSLSupportMethod.",
                rejected.getMessage());

        acmService.deleteCertificate(certificateArn, ACM_REGION);
    }

    /**
     * The certificate's location is one field and not both, so a request naming an ACM certificate
     * and an IAM certificate is refused although each of them exists.
     */
    @Test
    void createDistributionRefusesBothCertificateLocationsAtOnce() {
        String name = "cf-cert-both-" + suffix();
        ServerCertificate certificate = uploadCertificate(name);
        String certificateArn = importAcmCertificate().getArn();
        Map<String, String> viewerCertificate =
                completeViewerCertificate("ACMCertificateArn", certificateArn);
        viewerCertificate.put("IAMCertificateId", certificate.getServerCertificateId());

        AwsException rejected = assertThrows(AwsException.class, () -> cloudFrontService
                .createDistribution(distributionWithViewerCertificate(viewerCertificate), Map.of()));

        assertEquals("InvalidViewerCertificate", rejected.getErrorCode());
        assertEquals(400, rejected.getHttpStatus());
        assertEquals("You must specify a value for ACMCertificateArn or IAMCertificateId, not both.",
                rejected.getMessage());

        deleteServerCertificate(name).statusCode(200);
        acmService.deleteCertificate(certificateArn, ACM_REGION);
    }

    /** Both rules are read on the update too, and a refused update leaves the distribution as it was. */
    @Test
    void updateDistributionRefusesACertificateWithoutTheMembersItRequires() {
        String certificateArn = importAcmCertificate().getArn();
        Distribution created = cloudFrontService.createDistribution(
                distributionWithViewerCertificate("ACMCertificateArn", certificateArn), Map.of());
        Map<String, String> viewerCertificate =
                completeViewerCertificate("ACMCertificateArn", certificateArn);
        viewerCertificate.remove("SSLSupportMethod");

        AwsException rejected = assertThrows(AwsException.class,
                () -> cloudFrontService.updateDistribution(created.getId(), created.getEtag(),
                        distributionWithViewerCertificate(viewerCertificate)));

        assertEquals("InvalidViewerCertificate", rejected.getErrorCode());
        assertEquals("sni-only", cloudFrontService.getDistribution(created.getId())
                .getConfig().getViewerCertificate().get("SSLSupportMethod"));

        cloudFrontService.removeDistribution(created.getId());
        acmService.deleteCertificate(certificateArn, ACM_REGION);
    }

    @Test
    void updateDistributionRefusesBothCertificateLocationsAtOnce() {
        String name = "cf-cert-update-both-" + suffix();
        ServerCertificate certificate = uploadCertificate(name);
        Distribution created = cloudFrontService.createDistribution(
                distributionWithCertificate(certificate.getServerCertificateId()), Map.of());
        String certificateArn = importAcmCertificate().getArn();
        Map<String, String> viewerCertificate =
                completeViewerCertificate("IAMCertificateId", certificate.getServerCertificateId());
        viewerCertificate.put("ACMCertificateArn", certificateArn);

        AwsException rejected = assertThrows(AwsException.class,
                () -> cloudFrontService.updateDistribution(created.getId(), created.getEtag(),
                        distributionWithViewerCertificate(viewerCertificate)));

        assertEquals("InvalidViewerCertificate", rejected.getErrorCode());
        assertEquals("You must specify a value for ACMCertificateArn or IAMCertificateId, not both.",
                rejected.getMessage());

        cloudFrontService.removeDistribution(created.getId());
        deleteServerCertificate(name).statusCode(200);
        acmService.deleteCertificate(certificateArn, ACM_REGION);
    }

    /** The refusal reaches the wire as the REST XML error a distribution write answers with. */
    @Test
    void theRefusalReachesTheWireAsAnInvalidViewerCertificate() {
        String body = """
                <?xml version="1.0" encoding="UTF-8"?>
                <DistributionConfig xmlns="http://cloudfront.amazonaws.com/doc/2020-05-31/">
                  <CallerReference>incomplete-viewer-cert-%s</CallerReference>
                  <Origins>
                    <Quantity>1</Quantity>
                    <Items>
                      <Origin>
                        <Id>incomplete-cert-origin</Id>
                        <DomainName>example.com</DomainName>
                        <CustomOriginConfig>
                          <HTTPPort>80</HTTPPort>
                          <HTTPSPort>443</HTTPSPort>
                          <OriginProtocolPolicy>http-only</OriginProtocolPolicy>
                        </CustomOriginConfig>
                      </Origin>
                    </Items>
                  </Origins>
                  <DefaultCacheBehavior>
                    <TargetOriginId>incomplete-cert-origin</TargetOriginId>
                    <ViewerProtocolPolicy>allow-all</ViewerProtocolPolicy>
                  </DefaultCacheBehavior>
                  <ViewerCertificate>
                    <ACMCertificateArn>arn:aws:acm:us-east-1:000000000000:certificate/%s</ACMCertificateArn>
                    <SSLSupportMethod>sni-only</SSLSupportMethod>
                  </ViewerCertificate>
                  <Comment>incomplete-viewer-certificate</Comment>
                  <Enabled>false</Enabled>
                </DistributionConfig>
                """.formatted(suffix(), UUID.randomUUID());

        given()
                .contentType(ContentType.XML)
                .body(body)
            .when()
                .post("/2020-05-31/distribution")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidViewerCertificate"))
                .body("ErrorResponse.Error.Message",
                        equalTo("You must specify values for MinimumProtocolVersion and SSLSupportMethod."));
    }

    /**
     * A distribution on the CloudFront domain name sets {@code CloudFrontDefaultCertificate} and
     * leaves the other fields empty, so it names no certificate and neither rule reaches it.
     */
    @Test
    void aDistributionOnTheCloudFrontDomainNameNeedsNeitherMember() {
        Distribution created = cloudFrontService.createDistribution(
                distributionWithViewerCertificate(defaultViewerCertificate(null)), Map.of());

        assertEquals("true", cloudFrontService.getDistribution(created.getId())
                .getConfig().getViewerCertificate().get("CloudFrontDefaultCertificate"));

        cloudFrontService.removeDistribution(created.getId());
    }

    /**
     * {@code MinimumProtocolVersion} admits an enumeration, and a value outside it is what
     * {@code InvalidMinimumProtocolVersion} is documented for. The certificate named here matches
     * nothing, so what refuses the request is the member's shape rather than the lookup.
     */
    @Test
    void createDistributionRefusesASecurityPolicyOutsideTheEnumeration() {
        Map<String, String> viewerCertificate =
                completeViewerCertificate("IAMCertificateId", "ASCAMISSING");
        viewerCertificate.put("MinimumProtocolVersion", "TLSv1.2_2020");

        AwsException rejected = assertThrows(AwsException.class, () -> cloudFrontService
                .createDistribution(distributionWithViewerCertificate(viewerCertificate), Map.of()));

        assertEquals("InvalidMinimumProtocolVersion", rejected.getErrorCode());
        assertEquals(400, rejected.getHttpStatus());
        assertEquals("The minimum protocol version specified is not valid.", rejected.getMessage());
    }

    /**
     * Every security policy the member admits is taken, {@code vip} keeping {@code SSLv3} clear of
     * the one pair the two members cannot carry together.
     */
    @ParameterizedTest
    @ValueSource(strings = {"SSLv3", "TLSv1", "TLSv1_2016", "TLSv1.1_2016", "TLSv1.2_2018",
            "TLSv1.2_2019", "TLSv1.2_2021", "TLSv1.2_2025", "TLSv1.3_2025"})
    void everySecurityPolicyTheEnumerationAdmitsIsTaken(String protocolVersion) {
        String certificateArn = importAcmCertificate().getArn();
        Map<String, String> viewerCertificate =
                completeViewerCertificate("ACMCertificateArn", certificateArn);
        viewerCertificate.put("MinimumProtocolVersion", protocolVersion);
        viewerCertificate.put("SSLSupportMethod", "vip");

        Distribution created = cloudFrontService.createDistribution(
                distributionWithViewerCertificate(viewerCertificate), Map.of());

        assertEquals(protocolVersion, cloudFrontService.getDistribution(created.getId())
                .getConfig().getViewerCertificate().get("MinimumProtocolVersion"));

        cloudFrontService.removeDistribution(created.getId());
        acmService.deleteCertificate(certificateArn, ACM_REGION);
    }

    /**
     * {@code SSLSupportMethod} admits an enumeration too, and a value outside it has no code of its
     * own: it reads as the {@code InvalidArgument} this service answers a member outside its shape
     * with.
     */
    @Test
    void createDistributionRefusesAViewerSupportMethodOutsideTheEnumeration() {
        Map<String, String> viewerCertificate =
                completeViewerCertificate("IAMCertificateId", "ASCAMISSING");
        viewerCertificate.put("SSLSupportMethod", "sni");

        AwsException rejected = assertThrows(AwsException.class, () -> cloudFrontService
                .createDistribution(distributionWithViewerCertificate(viewerCertificate), Map.of()));

        assertEquals("InvalidArgument", rejected.getErrorCode());
        assertEquals(400, rejected.getHttpStatus());
        assertEquals("The SSLSupportMethod specified is not valid.", rejected.getMessage());
    }

    /** And every value that member admits is taken, {@code static-ip} included. */
    @ParameterizedTest
    @ValueSource(strings = {"sni-only", "vip", "static-ip"})
    void everyViewerSupportMethodTheEnumerationAdmitsIsTaken(String sslSupportMethod) {
        String certificateArn = importAcmCertificate().getArn();
        Map<String, String> viewerCertificate =
                completeViewerCertificate("ACMCertificateArn", certificateArn);
        viewerCertificate.put("SSLSupportMethod", sslSupportMethod);

        Distribution created = cloudFrontService.createDistribution(
                distributionWithViewerCertificate(viewerCertificate), Map.of());

        assertEquals(sslSupportMethod, cloudFrontService.getDistribution(created.getId())
                .getConfig().getViewerCertificate().get("SSLSupportMethod"));

        cloudFrontService.removeDistribution(created.getId());
        acmService.deleteCertificate(certificateArn, ACM_REGION);
    }

    /**
     * SNI carries a floor, "you must specify TLSv1 or higher", and {@code SSLv3} is the only value
     * below it, which is the pair {@code InvalidProtocolSettings} names.
     */
    @Test
    void createDistributionRefusesSslv3AlongsideSniOnly() {
        Map<String, String> viewerCertificate =
                completeViewerCertificate("IAMCertificateId", "ASCAMISSING");
        viewerCertificate.put("MinimumProtocolVersion", "SSLv3");

        AwsException rejected = assertThrows(AwsException.class, () -> cloudFrontService
                .createDistribution(distributionWithViewerCertificate(viewerCertificate), Map.of()));

        assertEquals("InvalidProtocolSettings", rejected.getErrorCode());
        assertEquals(400, rejected.getHttpStatus());
        assertEquals("You cannot specify SSLv3 as the minimum protocol version if you only want to "
                + "support only clients that support Server Name Indication (SNI).",
                rejected.getMessage());
    }

    /**
     * The same pair on the update, which has never declared {@code InvalidProtocolSettings}: it
     * answers the {@code InvalidMinimumProtocolVersion} it does declare, carrying the pair's own
     * published sentence, and the refused update leaves the distribution on the policy it had.
     */
    @Test
    void updateDistributionRefusesSslv3AlongsideSniOnly() {
        String certificateArn = importAcmCertificate().getArn();
        Distribution created = cloudFrontService.createDistribution(
                distributionWithViewerCertificate("ACMCertificateArn", certificateArn), Map.of());
        Map<String, String> viewerCertificate =
                completeViewerCertificate("ACMCertificateArn", certificateArn);
        viewerCertificate.put("MinimumProtocolVersion", "SSLv3");

        AwsException rejected = assertThrows(AwsException.class,
                () -> cloudFrontService.updateDistribution(created.getId(), created.getEtag(),
                        distributionWithViewerCertificate(viewerCertificate)));

        assertEquals("InvalidMinimumProtocolVersion", rejected.getErrorCode());
        assertEquals(400, rejected.getHttpStatus());
        assertEquals("You cannot specify SSLv3 as the minimum protocol version if you only want to "
                + "support only clients that support Server Name Indication (SNI).",
                rejected.getMessage());
        assertEquals("TLSv1.2_2021", cloudFrontService.getDistribution(created.getId())
                .getConfig().getViewerCertificate().get("MinimumProtocolVersion"));

        cloudFrontService.removeDistribution(created.getId());
        acmService.deleteCertificate(certificateArn, ACM_REGION);
    }

    /**
     * The update answers both protocol refusals with the one code it declares, and the sentences
     * keep them apart: {@code InvalidMinimumProtocolVersion}'s own for a value outside the
     * enumeration, and the pair's published one for {@code SSLv3} alongside {@code sni-only}.
     */
    @Test
    void theUpdateAnswersBothProtocolRefusalsWithTheCodeItDeclares() {
        String certificateArn = importAcmCertificate().getArn();
        Distribution created = cloudFrontService.createDistribution(
                distributionWithViewerCertificate("ACMCertificateArn", certificateArn), Map.of());
        Map<String, String> outsideTheSet =
                completeViewerCertificate("ACMCertificateArn", certificateArn);
        outsideTheSet.put("MinimumProtocolVersion", "TLSv1.4");

        AwsException outside = assertThrows(AwsException.class,
                () -> cloudFrontService.updateDistribution(created.getId(), created.getEtag(),
                        distributionWithViewerCertificate(outsideTheSet)));

        assertEquals("InvalidMinimumProtocolVersion", outside.getErrorCode());
        assertEquals("The minimum protocol version specified is not valid.", outside.getMessage());

        Map<String, String> pair = completeViewerCertificate("ACMCertificateArn", certificateArn);
        pair.put("MinimumProtocolVersion", "SSLv3");

        AwsException refused = assertThrows(AwsException.class,
                () -> cloudFrontService.updateDistribution(created.getId(), created.getEtag(),
                        distributionWithViewerCertificate(pair)));

        assertEquals(outside.getErrorCode(), refused.getErrorCode());
        assertEquals("You cannot specify SSLv3 as the minimum protocol version if you only want to "
                + "support only clients that support Server Name Indication (SNI).",
                refused.getMessage());

        cloudFrontService.removeDistribution(created.getId());
        acmService.deleteCertificate(certificateArn, ACM_REGION);
    }

    /**
     * A request that sets {@code CloudFrontDefaultCertificate} is told to leave the other fields
     * empty, so a certificate named beside it contradicts the request's own claim. Neither value
     * here is looked up: the rule is read first.
     */
    @ParameterizedTest
    @ValueSource(strings = {"ACMCertificateArn", "IAMCertificateId"})
    void createDistributionRefusesACertificateAlongsideTheDefaultCertificate(String member) {
        Map<String, String> viewerCertificate = completeViewerCertificate(member,
                "ACMCertificateArn".equals(member)
                        ? "arn:aws:acm:us-east-1:000000000000:certificate/" + UUID.randomUUID()
                        : "ASCAMISSING");
        viewerCertificate.put("CloudFrontDefaultCertificate", "true");

        AwsException rejected = assertThrows(AwsException.class, () -> cloudFrontService
                .createDistribution(distributionWithViewerCertificate(viewerCertificate), Map.of()));

        assertEquals("InvalidViewerCertificate", rejected.getErrorCode());
        assertEquals(400, rejected.getHttpStatus());
        assertEquals("You cannot specify ACMCertificateArn or IAMCertificateId when "
                + "CloudFrontDefaultCertificate is true.", rejected.getMessage());
    }

    /** The contradiction is read on the update too, which keeps the certificate it already had. */
    @Test
    void updateDistributionRefusesACertificateAlongsideTheDefaultCertificate() {
        String certificateArn = importAcmCertificate().getArn();
        Distribution created = cloudFrontService.createDistribution(
                distributionWithViewerCertificate("ACMCertificateArn", certificateArn), Map.of());
        Map<String, String> viewerCertificate =
                completeViewerCertificate("ACMCertificateArn", certificateArn);
        viewerCertificate.put("CloudFrontDefaultCertificate", "true");

        AwsException rejected = assertThrows(AwsException.class,
                () -> cloudFrontService.updateDistribution(created.getId(), created.getEtag(),
                        distributionWithViewerCertificate(viewerCertificate)));

        assertEquals("InvalidViewerCertificate", rejected.getErrorCode());
        assertEquals(certificateArn, cloudFrontService.getDistribution(created.getId())
                .getConfig().getViewerCertificate().get("ACMCertificateArn"));

        cloudFrontService.removeDistribution(created.getId());
        acmService.deleteCertificate(certificateArn, ACM_REGION);
    }

    /**
     * The pair is not read for a distribution on the CloudFront certificate: the policy it asks
     * for is replaced with {@code TLSv1} "regardless of the value that you set here", so the value
     * that would make the pair is discarded and refusing the request for it would contradict the
     * override. The distribution is taken and reads back the policy it is really on.
     */
    @Test
    void sslv3WithSniOnlyIsTakenOnTheDefaultCertificateWhosePolicyIsReplaced() {
        Map<String, String> viewerCertificate = defaultViewerCertificate("SSLv3");
        viewerCertificate.put("SSLSupportMethod", "sni-only");

        Distribution created = cloudFrontService.createDistribution(
                distributionWithViewerCertificate(viewerCertificate), Map.of());

        assertEquals("TLSv1", cloudFrontService.getDistribution(created.getId())
                .getConfig().getViewerCertificate().get("MinimumProtocolVersion"));

        Distribution updated = cloudFrontService.updateDistribution(created.getId(),
                created.getEtag(), distributionWithViewerCertificate(viewerCertificate));

        assertEquals("TLSv1", updated.getConfig().getViewerCertificate().get("MinimumProtocolVersion"));

        cloudFrontService.removeDistribution(created.getId());
    }

    /**
     * The claim itself is a member declared {@code boolean}, so text that is no boolean at all is
     * refused rather than taken for false. Read for the word {@code true} alone, a request saying
     * {@code yes} would dodge every rule the member governs.
     */
    @ParameterizedTest
    @ValueSource(strings = {"yes", "no", "TRUE1", "2", "t", "on", "null"})
    void createDistributionRefusesADefaultCertificateClaimThatIsNoBoolean(String claimed) {
        Map<String, String> viewerCertificate = new LinkedHashMap<>();
        viewerCertificate.put("CloudFrontDefaultCertificate", claimed);

        AwsException rejected = assertThrows(AwsException.class, () -> cloudFrontService
                .createDistribution(distributionWithViewerCertificate(viewerCertificate), Map.of()));

        assertEquals("InvalidArgument", rejected.getErrorCode());
        assertEquals(400, rejected.getHttpStatus());
        assertEquals("The CloudFrontDefaultCertificate specified is not valid.",
                rejected.getMessage());
    }

    /**
     * A claim that is no boolean is refused even beside a certificate of the request's own, where
     * taking it for false would have stored the distribution on that certificate instead.
     */
    @Test
    void createDistributionRefusesADefaultCertificateClaimThatIsNoBooleanBesideACertificate() {
        String certificateArn = importAcmCertificate().getArn();
        Map<String, String> viewerCertificate =
                completeViewerCertificate("ACMCertificateArn", certificateArn);
        viewerCertificate.put("CloudFrontDefaultCertificate", "yes");

        AwsException rejected = assertThrows(AwsException.class, () -> cloudFrontService
                .createDistribution(distributionWithViewerCertificate(viewerCertificate), Map.of()));

        assertEquals("InvalidArgument", rejected.getErrorCode());

        acmService.deleteCertificate(certificateArn, ACM_REGION);
    }

    /** The update reads the claim's shape too, and keeps the certificate it already had. */
    @Test
    void updateDistributionRefusesADefaultCertificateClaimThatIsNoBoolean() {
        String certificateArn = importAcmCertificate().getArn();
        Distribution created = cloudFrontService.createDistribution(
                distributionWithViewerCertificate("ACMCertificateArn", certificateArn), Map.of());
        Map<String, String> viewerCertificate = new LinkedHashMap<>();
        viewerCertificate.put("CloudFrontDefaultCertificate", "yes");

        AwsException rejected = assertThrows(AwsException.class,
                () -> cloudFrontService.updateDistribution(created.getId(), created.getEtag(),
                        distributionWithViewerCertificate(viewerCertificate)));

        assertEquals("InvalidArgument", rejected.getErrorCode());
        assertEquals(certificateArn, cloudFrontService.getDistribution(created.getId())
                .getConfig().getViewerCertificate().get("ACMCertificateArn"));

        cloudFrontService.removeDistribution(created.getId());
        acmService.deleteCertificate(certificateArn, ACM_REGION);
    }

    /**
     * Every form XML gives a boolean is taken, and the two that mean true put the distribution on
     * the CloudFront certificate, where the security policy is the one that comes with it.
     */
    @ParameterizedTest
    @ValueSource(strings = {"true", "TRUE", "True", "1"})
    void everyBooleanFormThatMeansTrueClaimsTheDefaultCertificate(String claimed) {
        Map<String, String> viewerCertificate = new LinkedHashMap<>();
        viewerCertificate.put("CloudFrontDefaultCertificate", claimed);
        viewerCertificate.put("MinimumProtocolVersion", "TLSv1.2_2021");

        Distribution created = cloudFrontService.createDistribution(
                distributionWithViewerCertificate(viewerCertificate), Map.of());

        assertEquals("TLSv1", cloudFrontService.getDistribution(created.getId())
                .getConfig().getViewerCertificate().get("MinimumProtocolVersion"));

        cloudFrontService.removeDistribution(created.getId());
    }

    /**
     * The forms that mean false leave the distribution on a certificate of its own, so the members
     * that come with one are required and the security policy asked for is kept.
     */
    @ParameterizedTest
    @ValueSource(strings = {"false", "FALSE", "0"})
    void everyBooleanFormThatMeansFalseLeavesTheDistributionOnItsOwnCertificate(String claimed) {
        String certificateArn = importAcmCertificate().getArn();
        Map<String, String> viewerCertificate =
                completeViewerCertificate("ACMCertificateArn", certificateArn);
        viewerCertificate.put("CloudFrontDefaultCertificate", claimed);

        Distribution created = cloudFrontService.createDistribution(
                distributionWithViewerCertificate(viewerCertificate), Map.of());

        assertEquals("TLSv1.2_2021", cloudFrontService.getDistribution(created.getId())
                .getConfig().getViewerCertificate().get("MinimumProtocolVersion"));

        cloudFrontService.removeDistribution(created.getId());
        acmService.deleteCertificate(certificateArn, ACM_REGION);
    }

    /**
     * The security policy a distribution on the CloudFront domain name asks for is replaced rather
     * than refused: CloudFront "automatically sets the security policy to TLSv1 regardless of the
     * value that you set here". The map the caller handed in is left as it was.
     */
    @Test
    void theDefaultCertificateCarriesTlsv1WhateverTheRequestAsksFor() {
        Map<String, String> asked = defaultViewerCertificate("TLSv1.2_2021");

        Distribution created = cloudFrontService.createDistribution(
                distributionWithViewerCertificate(asked), Map.of());

        assertEquals("TLSv1", cloudFrontService.getDistribution(created.getId())
                .getConfig().getViewerCertificate().get("MinimumProtocolVersion"));
        assertEquals("TLSv1.2_2021", asked.get("MinimumProtocolVersion"));

        Distribution updated = cloudFrontService.updateDistribution(created.getId(),
                created.getEtag(),
                distributionWithViewerCertificate(defaultViewerCertificate("TLSv1.3_2025")));

        assertEquals("TLSv1", updated.getConfig().getViewerCertificate().get("MinimumProtocolVersion"));

        cloudFrontService.removeDistribution(created.getId());
    }

    /**
     * A request carrying no viewer certificate at all reads back the same way on the wire: the
     * CloudFront default certificate, on the security policy that comes with it.
     */
    @Test
    void aDistributionWithNoViewerCertificateReadsAsTheDefaultCertificateOnTlsv1() {
        String body = """
                <?xml version="1.0" encoding="UTF-8"?>
                <DistributionConfig xmlns="http://cloudfront.amazonaws.com/doc/2020-05-31/">
                  <CallerReference>no-viewer-cert-%s</CallerReference>
                  <Origins>
                    <Quantity>1</Quantity>
                    <Items>
                      <Origin>
                        <Id>no-cert-origin</Id>
                        <DomainName>example.com</DomainName>
                        <CustomOriginConfig>
                          <HTTPPort>80</HTTPPort>
                          <HTTPSPort>443</HTTPSPort>
                          <OriginProtocolPolicy>http-only</OriginProtocolPolicy>
                        </CustomOriginConfig>
                      </Origin>
                    </Items>
                  </Origins>
                  <DefaultCacheBehavior>
                    <TargetOriginId>no-cert-origin</TargetOriginId>
                    <ViewerProtocolPolicy>allow-all</ViewerProtocolPolicy>
                  </DefaultCacheBehavior>
                  <Comment>no-viewer-certificate</Comment>
                  <Enabled>false</Enabled>
                </DistributionConfig>
                """.formatted(suffix());

        String location = given()
                .contentType(ContentType.XML)
                .body(body)
            .when()
                .post("/2020-05-31/distribution")
            .then()
                .statusCode(201)
                .body("Distribution.DistributionConfig.ViewerCertificate.CloudFrontDefaultCertificate",
                        equalTo("true"))
                .body("Distribution.DistributionConfig.ViewerCertificate.MinimumProtocolVersion",
                        equalTo("TLSv1"))
                .extract().header("Location");

        cloudFrontService.removeDistribution(location.substring(location.lastIndexOf('/') + 1));
    }
}
