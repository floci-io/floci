package io.github.hectorvent.floci.services.cloudfront;

import io.github.hectorvent.floci.services.cloudfront.model.DefaultCacheBehavior;
import io.github.hectorvent.floci.services.cloudfront.model.CloudFrontOriginAccessIdentity;
import io.github.hectorvent.floci.services.cloudfront.model.Distribution;
import io.github.hectorvent.floci.services.cloudfront.model.DistributionConfig;
import io.github.hectorvent.floci.services.cloudfront.model.Origin;
import io.github.hectorvent.floci.services.cloudfront.model.OriginAccessControl;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(CloudFrontS3AuthServingTest.S3AuthProfile.class)
class CloudFrontS3AuthServingTest {

    @Inject
    CloudFrontService cloudFrontService;

    @Inject
    S3Service s3Service;

    @Test
    void enforcesAnonymousS3OriginReadAccess() {
        String suffix = Long.toString(System.nanoTime(), 36);
        Distribution privateDistribution =
                distribution("cf-private-" + suffix, false, null, null);
        Distribution publicDistribution =
                distribution("cf-public-" + suffix, true, null, null);

        given().header("Host", privateDistribution.getDomainName()).when().get("/index.html")
                .then().statusCode(403);
        given().header("Host", publicDistribution.getDomainName()).when().get("/index.html")
                .then().statusCode(200).body(equalTo("PUBLIC-ORIGIN"));
    }

    @Test
    void servesPrivateS3OriginThroughOacPolicyGrant() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String bucket = "cf-oac-" + suffix;
        OriginAccessControl oac = new OriginAccessControl();
        oac.setName("oac-" + suffix);
        oac.setSigningBehavior("always");
        oac.setSigningProtocol("sigv4");
        oac.setOriginAccessControlOriginType("s3");
        oac = cloudFrontService.createOriginAccessControl(oac);

        Distribution distribution = distribution(bucket, false, oac.getId(), null);
        s3Service.putBucketPolicy(
                bucket, oacReadPolicy(bucket, distribution.getArn()));

        given().header("Host", distribution.getDomainName()).when().get("/index.html")
                .then().statusCode(200).body(equalTo("PRIVATE-ORIGIN"));
    }

    @Test
    void servesPrivateS3OriginThroughOaiObjectAclGrant() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String bucket = "cf-oai-" + suffix;
        CloudFrontOriginAccessIdentity oai = new CloudFrontOriginAccessIdentity();
        oai.setCallerReference("oai-" + suffix);
        oai.setComment("Private origin access");
        oai = cloudFrontService.createCloudFrontOriginAccessIdentity(oai);

        Distribution distribution = distribution(
                bucket,
                false,
                null,
                "origin-access-identity/cloudfront/" + oai.getId());
        s3Service.putObjectAcl(
                bucket,
                "index.html",
                null,
                canonicalReadAcl(oai.getS3CanonicalUserId()),
                null,
                null,
                null,
                null,
                null,
                null);

        given().header("Host", distribution.getDomainName()).when().get("/index.html")
                .then().statusCode(200).body(equalTo("PRIVATE-ORIGIN"));
    }

    @Test
    void oacNeverSigningRequiresPublicS3Access() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String bucket = "cf-oac-never-" + suffix;
        OriginAccessControl oac = new OriginAccessControl();
        oac.setName("oac-never-" + suffix);
        oac.setSigningBehavior("never");
        oac.setSigningProtocol("sigv4");
        oac.setOriginAccessControlOriginType("s3");
        oac = cloudFrontService.createOriginAccessControl(oac);

        Distribution distribution = distribution(bucket, false, oac.getId(), null);
        s3Service.putBucketPolicy(
                bucket, oacReadPolicy(bucket, distribution.getArn()));

        given().header("Host", distribution.getDomainName()).when().get("/index.html")
                .then().statusCode(403);
    }

    @Test
    void oacNoOverridePreservesViewerAuthorization() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String bucket = "cf-oac-viewer-auth-" + suffix;
        OriginAccessControl oac = new OriginAccessControl();
        oac.setName("oac-viewer-auth-" + suffix);
        oac.setSigningBehavior("no-override");
        oac.setSigningProtocol("sigv4");
        oac.setOriginAccessControlOriginType("s3");
        oac = cloudFrontService.createOriginAccessControl(oac);

        Distribution distribution = distribution(bucket, false, oac.getId(), null);

        given()
                .header("Host", distribution.getDomainName())
                .header("Authorization", viewerAuthorization("test"))
        .when()
                .get("/index.html")
        .then()
                .statusCode(200)
                .body(equalTo("PRIVATE-ORIGIN"));

        given()
                .header("Host", distribution.getDomainName())
                .header("Authorization", viewerAuthorization("unknown"))
        .when()
                .get("/index.html")
        .then()
                .statusCode(403);
    }

    private Distribution distribution(
            String bucket, boolean publicRead, String oacId, String originAccessIdentity) {
        s3Service.createBucket(bucket, "us-east-1");
        String body = publicRead ? "PUBLIC-ORIGIN" : "PRIVATE-ORIGIN";
        s3Service.putObject(bucket, "index.html", body.getBytes(StandardCharsets.UTF_8),
                "text/html", Map.of());
        if (publicRead) {
            s3Service.putBucketPolicy(bucket, publicReadPolicy(bucket));
        }

        Origin origin = new Origin();
        origin.setId("s3-origin");
        origin.setDomainName(bucket + ".s3.us-east-1.amazonaws.com");
        origin.setOriginAccessControlId(oacId);
        origin.setS3OriginConfig(new LinkedHashMap<>(Map.of(
                "OriginAccessIdentity",
                originAccessIdentity != null ? originAccessIdentity : "")));
        DefaultCacheBehavior behavior = new DefaultCacheBehavior();
        behavior.setTargetOriginId(origin.getId());
        behavior.setViewerProtocolPolicy("allow-all");
        DistributionConfig config = new DistributionConfig();
        config.setEnabled(true);
        config.setOrigins(List.of(origin));
        config.setDefaultCacheBehavior(behavior);
        Distribution distribution = new Distribution();
        distribution.setConfig(config);
        return cloudFrontService.createDistribution(distribution, Map.of());
    }

    private static String publicReadPolicy(String bucket) {
        return """
                {
                  "Version": "2012-10-17",
                  "Statement": {
                    "Effect": "Allow",
                    "Principal": "*",
                    "Action": "s3:GetObject",
                    "Resource": "arn:aws:s3:::%s/*"
                  }
                }
                """.formatted(bucket);
    }

    private static String oacReadPolicy(String bucket, String distributionArn) {
        return """
                {
                  "Version": "2012-10-17",
                  "Statement": {
                    "Effect": "Allow",
                    "Principal": {"Service": "cloudfront.amazonaws.com"},
                    "Action": "s3:GetObject",
                    "Resource": "arn:aws:s3:::%s/*",
                    "Condition": {
                      "StringEquals": {"AWS:SourceArn": "%s"}
                    }
                  }
                }
                """.formatted(bucket, distributionArn);
    }

    private static String canonicalReadAcl(String canonicalUserId) {
        return """
                <AccessControlPolicy>
                  <AccessControlList>
                    <Grant>
                      <Grantee xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                               xsi:type="CanonicalUser">
                        <ID>%s</ID>
                      </Grantee>
                      <Permission>READ</Permission>
                    </Grant>
                  </AccessControlList>
                </AccessControlPolicy>
                """.formatted(canonicalUserId);
    }

    private static String viewerAuthorization(String accessKeyId) {
        return "AWS4-HMAC-SHA256 Credential=" + accessKeyId
                + "/20260730/us-east-1/s3/aws4_request, "
                + "SignedHeaders=host;x-amz-date, Signature=abc123";
    }

    public static final class S3AuthProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.s3.enforce-auth", "true");
        }
    }
}
