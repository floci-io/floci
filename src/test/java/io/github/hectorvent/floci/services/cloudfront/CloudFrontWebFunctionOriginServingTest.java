package io.github.hectorvent.floci.services.cloudfront;

import io.github.hectorvent.floci.services.cloudfront.model.DefaultCacheBehavior;
import io.github.hectorvent.floci.services.cloudfront.model.Distribution;
import io.github.hectorvent.floci.services.cloudfront.model.DistributionConfig;
import io.github.hectorvent.floci.services.cloudfront.model.Origin;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.lambdaweb.LambdaWebCodeBuckets;
import io.github.hectorvent.floci.services.lambdaweb.LambdaWebRoles;
import io.github.hectorvent.floci.services.lambdaweb.LambdaWebZips;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;

/**
 * Distributions whose origin is a Web Function endpoint of this Floci, answered by the platform
 * without an execution environment, so no Docker. No private origin host is allowed here.
 */
@QuarkusTest
class CloudFrontWebFunctionOriginServingTest {

    private static final String BASE = "/2025-03-07";
    private static final String BUCKET = "web-code-cloudfront";
    private static final String ROLE = "arn:aws:iam::000000000000:role/web";
    private static final String CACHING_DISABLED = "4135ea2d-6df8-44a3-9df3-4b5a84be39ad";

    @Inject
    CloudFrontService cloudFrontService;

    @Inject
    S3Service s3Service;

    @Inject
    IamService iamService;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void anEndpointOriginIsReachedWhateverPortAndProtocolTheOriginNames() {
        String functionName = "cf-disabled-" + Long.toString(System.nanoTime(), 36);
        String domain = createFunction(s3Service, iamService, cleanup, functionName, "ApplicationManaged");
        given().contentType("application/json").body("{\"throttleConfig\": {\"rateLimit\": 0}}")
                .when().patch(BASE + "/web-functions/" + functionName + "/endpoints/dev")
                .then().statusCode(202);
        Distribution created = createDistribution(cloudFrontService, cleanup, domain,
                CloudFrontService.MANAGED_ALL_VIEWER_EXCEPT_HOST_HEADER_ORIGIN_REQUEST_POLICY_ID);

        given().header("Host", created.getDomainName()).when().get("/")
                .then().statusCode(503)
                .header("lambda-web-error-type", "Platform.EndpointDisabled")
                .header("lambda-web-request-id", matchesPattern("[A-Z2-7]{26}"));
    }

    @Test
    void anIamAuthEndpointRefusesTheUnsignedRequestOfADistributionWithoutOriginAccessControl() {
        String domain = createFunction(s3Service, iamService, cleanup, "cf-iam-" + Long.toString(System.nanoTime(), 36), "IamAuth");
        Distribution created = createDistribution(cloudFrontService, cleanup, domain,
                CloudFrontService.MANAGED_ALL_VIEWER_EXCEPT_HOST_HEADER_ORIGIN_REQUEST_POLICY_ID);

        given().header("Host", created.getDomainName()).when().get("/")
                .then().statusCode(403)
                .header("content-type", "text/html")
                .header("lambda-web-request-id", matchesPattern("[A-Z2-7]{26}"))
                .body(containsString("You are not authorized to access this endpoint."));
    }

    @Test
    void forwardingTheViewerHostToAnEndpointIsABadGateway() {
        String domain = createFunction(s3Service, iamService, cleanup, "cf-host-" + Long.toString(System.nanoTime(), 36),
                "ApplicationManaged");
        Distribution created = createDistribution(cloudFrontService, cleanup, domain,
                CloudFrontService.MANAGED_ALL_VIEWER_ORIGIN_REQUEST_POLICY_ID);

        given().header("Host", created.getDomainName()).when().get("/")
                .then().statusCode(502);
    }

    static String createFunction(S3Service s3Service, IamService iamService, PartitionCleanup cleanup,
                                 String functionName, String authType) {
        LambdaWebRoles.trustLambda(iamService, ROLE);
        LambdaWebCodeBuckets.create(s3Service, BUCKET);
        s3Service.putObject(BUCKET, "app.zip", LambdaWebZips.indexJs(""), "application/zip", Map.of());
        String domain = given().contentType("application/json")
                .body("""
                        {"functionName": "%s",
                         "revisionConfig": {
                           "buildConfig": {"codeConfig": {"s3Object": {"bucket": "%s", "key": "app.zip"}},
                                           "runtimeConfig": {"runtime": "nodejs24.x"}},
                           "serviceConfig": {"executionRoleArn": "%s"}},
                         "endpointConfig": {"endpointName": "dev", "endpointType": "HomeRegion",
                                            "authType": "%s", "autoDeploymentMode": "LatestRevision"}}
                        """.formatted(functionName, BUCKET, ROLE, authType))
                .when().put(BASE + "/web-functions")
                .then().statusCode(202)
                .extract().path("endpoint.domainName");
        cleanup.register(() -> given().when().delete(BASE + "/web-functions/" + functionName));
        return domain;
    }

    /** The origin names the endpoint as on AWS: its host without a port, over HTTPS on 443. */
    static Distribution createDistribution(CloudFrontService cloudFrontService, PartitionCleanup cleanup,
                                           String endpointDomain, String originRequestPolicyId) {
        Origin origin = new Origin();
        origin.setId("web");
        origin.setDomainName(endpointDomain.substring(0, endpointDomain.lastIndexOf(':')));
        Map<String, Object> customOriginConfig = new LinkedHashMap<>();
        customOriginConfig.put("HTTPPort", "80");
        customOriginConfig.put("HTTPSPort", "443");
        customOriginConfig.put("OriginProtocolPolicy", "https-only");
        origin.setCustomOriginConfig(customOriginConfig);
        DefaultCacheBehavior behavior = new DefaultCacheBehavior();
        behavior.setTargetOriginId("web");
        behavior.setViewerProtocolPolicy("allow-all");
        behavior.setCachePolicyId(CACHING_DISABLED);
        behavior.setOriginRequestPolicyId(originRequestPolicyId);
        DistributionConfig config = new DistributionConfig();
        config.setEnabled(true);
        config.setOrigins(List.of(origin));
        config.setDefaultCacheBehavior(behavior);
        Distribution distribution = new Distribution();
        distribution.setConfig(config);
        Distribution created = cloudFrontService.createDistribution(distribution, Map.of());
        cleanup.register(() -> cloudFrontService.removeDistribution(created.getId()));
        return created;
    }
}
