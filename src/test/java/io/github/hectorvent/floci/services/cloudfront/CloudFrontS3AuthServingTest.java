package io.github.hectorvent.floci.services.cloudfront;

import io.github.hectorvent.floci.services.cloudfront.model.DefaultCacheBehavior;
import io.github.hectorvent.floci.services.cloudfront.model.Distribution;
import io.github.hectorvent.floci.services.cloudfront.model.DistributionConfig;
import io.github.hectorvent.floci.services.cloudfront.model.Origin;
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
        Distribution privateDistribution = distribution("cf-private-" + suffix, false);
        Distribution publicDistribution = distribution("cf-public-" + suffix, true);

        given().header("Host", privateDistribution.getDomainName()).when().get("/index.html")
                .then().statusCode(403);
        given().header("Host", publicDistribution.getDomainName()).when().get("/index.html")
                .then().statusCode(200).body(equalTo("PUBLIC-ORIGIN"));
    }

    private Distribution distribution(String bucket, boolean publicRead) {
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
        origin.setS3OriginConfig(new LinkedHashMap<>(Map.of("OriginAccessIdentity", "")));
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

    public static final class S3AuthProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.s3.enforce-auth", "true");
        }
    }
}
