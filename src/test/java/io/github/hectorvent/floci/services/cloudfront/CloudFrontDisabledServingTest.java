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
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
@TestProfile(CloudFrontDisabledServingTest.CloudFrontDisabledProfile.class)
class CloudFrontDisabledServingTest {

    @Inject
    CloudFrontService cloudFrontService;

    @Inject
    S3Service s3Service;

    @Test
    void serviceDisablementBlocksViewerAndInternalRoutes() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String bucket = "cf-service-disabled-" + suffix;
        s3Service.createBucket(bucket, "us-east-1");
        s3Service.putObject(bucket, "index.html", "MUST-NOT-SERVE".getBytes(StandardCharsets.UTF_8),
                "text/html", Map.of());

        Origin origin = new Origin();
        origin.setId("s3-origin");
        origin.setDomainName(bucket + ".s3.us-east-1.amazonaws.com");
        origin.setS3OriginConfig(new LinkedHashMap<>(Map.of("OriginAccessIdentity", "")));
        DefaultCacheBehavior behavior = new DefaultCacheBehavior();
        behavior.setTargetOriginId(origin.getId());
        behavior.setViewerProtocolPolicy("allow-all");
        DistributionConfig config = new DistributionConfig();
        config.setEnabled(true);
        config.setDefaultRootObject("index.html");
        config.setOrigins(List.of(origin));
        config.setDefaultCacheBehavior(behavior);
        Distribution distribution = new Distribution();
        distribution.setConfig(config);
        Distribution created = cloudFrontService.createDistribution(distribution, Map.of());

        given().header("Host", created.getDomainName()).when().get("/")
                .then().statusCode(400).body(containsString("ServiceNotAvailableException"));
        given().when().get("/_cloudfront/" + created.getId() + "/")
                .then().statusCode(400).body(containsString("ServiceNotAvailableException"));
    }

    public static final class CloudFrontDisabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.cloudfront.enabled", "false");
        }
    }
}
