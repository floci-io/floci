package io.github.hectorvent.floci.services.cloudfront;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.services.cloudfront.model.DefaultCacheBehavior;
import io.github.hectorvent.floci.services.cloudfront.model.Distribution;
import io.github.hectorvent.floci.services.cloudfront.model.DistributionConfig;
import io.github.hectorvent.floci.services.cloudfront.model.Origin;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.xml.HasXPath.hasXPath;

/**
 * DistributionConfig members that a caller may omit on input but still expects to read
 * back. Logging is "Required: No" on the request, and CloudFront reports it on every
 * response with its defaults; the AWS SDKs and the Terraform provider read it
 * unconditionally.
 */
@QuarkusTest
class CloudFrontDistributionConfigMembersTest {

    private static final String DISTRIBUTIONS = "/2020-05-31/distribution";
    private static final String API = DISTRIBUTIONS + "/";
    private static final String LOGGING_ENABLED =
            "//*[local-name()='Logging']/*[local-name()='Enabled']";
    private static final String LOGGING_INCLUDE_COOKIES =
            "//*[local-name()='Logging']/*[local-name()='IncludeCookies']";
    private static final String LOGGING_BUCKET =
            "//*[local-name()='Logging']/*[local-name()='Bucket']";
    private static final String LOGGING_PREFIX =
            "//*[local-name()='Logging']/*[local-name()='Prefix']";

    @Inject
    CloudFrontService cloudFrontService;

    /** The minimum a caller can legally supply: no Logging element at all. */
    private String createMinimalDistribution(String originId) {
        Origin origin = new Origin();
        origin.setId(originId);
        origin.setDomainName("example.com");
        Map<String, Object> customOriginConfig = new LinkedHashMap<>();
        customOriginConfig.put("HTTPPort", "80");
        customOriginConfig.put("HTTPSPort", "443");
        customOriginConfig.put("OriginProtocolPolicy", "http-only");
        origin.setCustomOriginConfig(customOriginConfig);

        DefaultCacheBehavior behavior = new DefaultCacheBehavior();
        behavior.setTargetOriginId(originId);
        behavior.setViewerProtocolPolicy("allow-all");

        DistributionConfig config = new DistributionConfig();
        config.setEnabled(true);
        config.setComment("members-test");
        config.setOrigins(List.of(origin));
        config.setDefaultCacheBehavior(behavior);

        Distribution distribution = new Distribution();
        distribution.setConfig(config);
        return cloudFrontService.createDistribution(distribution, Map.of()).getId();
    }

    @Test
    void getDistributionReportsLoggingDisabledByDefault() {
        String id = createMinimalDistribution("origin-logging");

        given()
        .when()
            .get(API + id)
        .then()
            .statusCode(200)
            .body(hasXPath(LOGGING_ENABLED, equalTo("false")))
            .body(hasXPath(LOGGING_BUCKET, equalTo("")));
    }

    @Test
    void getDistributionConfigReportsLoggingToo() {
        // The Terraform provider reads the resource back immediately after create, so the
        // config response has to carry the member too: reporting it only on
        // GetDistribution would still leave the read-after-create short a field.
        String id = createMinimalDistribution("origin-logging-config");

        given()
        .when()
            .get(API + id + "/config")
        .then()
            .statusCode(200)
            .body(hasXPath(LOGGING_ENABLED, equalTo("false")));
    }

    @Test
    void anExplicitLoggingConfigurationIsReportedBackInFull() {
        Origin origin = new Origin();
        origin.setId("origin-logging-explicit");
        origin.setDomainName("example.com");
        Map<String, Object> customOriginConfig = new LinkedHashMap<>();
        customOriginConfig.put("HTTPPort", "80");
        customOriginConfig.put("HTTPSPort", "443");
        customOriginConfig.put("OriginProtocolPolicy", "http-only");
        origin.setCustomOriginConfig(customOriginConfig);

        DefaultCacheBehavior behavior = new DefaultCacheBehavior();
        behavior.setTargetOriginId("origin-logging-explicit");
        behavior.setViewerProtocolPolicy("allow-all");

        DistributionConfig config = new DistributionConfig();
        config.setEnabled(true);
        config.setComment("members-test-explicit");
        config.setOrigins(List.of(origin));
        config.setDefaultCacheBehavior(behavior);
        Map<String, Object> logging = new LinkedHashMap<>();
        logging.put("Enabled", "true");
        logging.put("IncludeCookies", "true");
        logging.put("Bucket", "logs.s3.amazonaws.com");
        logging.put("Prefix", "cf/");
        config.setLogging(logging);

        Distribution distribution = new Distribution();
        distribution.setConfig(config);
        String id = cloudFrontService.createDistribution(distribution, Map.of()).getId();

        given()
        .when()
            .get(API + id)
        .then()
            .statusCode(200)
            .body(hasXPath(LOGGING_ENABLED, equalTo("true")))
            .body(hasXPath(LOGGING_INCLUDE_COOKIES, equalTo("true")))
            .body(hasXPath(LOGGING_BUCKET, equalTo("logs.s3.amazonaws.com")))
            .body(hasXPath(LOGGING_PREFIX, equalTo("cf/")));
    }

    /**
     * A DistributionConfig request body, which is what an SDK or the Terraform provider
     * actually sends. {@code loggingBlock} goes in verbatim so a caller that omits Logging
     * and a caller that supplies it share one shape.
     */
    private static String distributionConfigBody(String originId, String loggingBlock) {
        return """
                <DistributionConfig xmlns="http://cloudfront.amazonaws.com/doc/2020-05-31/">
                  <CallerReference>%s</CallerReference>
                  <Enabled>true</Enabled>
                  <Comment>members-test-request-path</Comment>
                  %s
                  <Origins><Quantity>1</Quantity><Items><Origin>
                    <Id>%s</Id><DomainName>example.com</DomainName>
                    <CustomOriginConfig><HTTPPort>80</HTTPPort><HTTPSPort>443</HTTPSPort>
                      <OriginProtocolPolicy>http-only</OriginProtocolPolicy></CustomOriginConfig>
                  </Origin></Items></Origins>
                  <DefaultCacheBehavior>
                    <TargetOriginId>%s</TargetOriginId>
                    <ViewerProtocolPolicy>allow-all</ViewerProtocolPolicy>
                  </DefaultCacheBehavior>
                </DistributionConfig>
                """.formatted(originId, loggingBlock, originId, originId);
    }

    /** POSTs a config body and returns the id of the distribution it created. */
    private static String postDistribution(String body) {
        String response = given()
                .contentType(ContentType.XML)
                .body(body)
        .when()
            .post(DISTRIBUTIONS)
        .then()
            .statusCode(201)
            .extract().asString();
        // Distribution.Id is the first Id in the response; the origin ids come later.
        return XmlParser.extractFirst(response, "Id", null);
    }

    @Test
    void createDistributionParsesAnExplicitLoggingBlock() {
        // Goes through the request path rather than the service, so the parser is what is
        // under test: emitting Logging on reads while the parser drops it on writes still
        // drifts, because the provider sends a configuration and reads defaults back.
        String id = postDistribution(distributionConfigBody("origin-logging-request", """
                <Logging>
                  <Enabled>true</Enabled>
                  <IncludeCookies>true</IncludeCookies>
                  <Bucket>request-logs.s3.amazonaws.com</Bucket>
                  <Prefix>request/</Prefix>
                </Logging>"""));

        given()
        .when()
            .get(API + id + "/config")
        .then()
            .statusCode(200)
            .body(hasXPath(LOGGING_ENABLED, equalTo("true")))
            .body(hasXPath(LOGGING_INCLUDE_COOKIES, equalTo("true")))
            .body(hasXPath(LOGGING_BUCKET, equalTo("request-logs.s3.amazonaws.com")))
            .body(hasXPath(LOGGING_PREFIX, equalTo("request/")));
    }

    @Test
    void createDistributionWithoutALoggingBlockKeepsTheDisabledDefaults() {
        String id = postDistribution(distributionConfigBody("origin-logging-request-absent", ""));

        given()
        .when()
            .get(API + id + "/config")
        .then()
            .statusCode(200)
            .body(hasXPath(LOGGING_ENABLED, equalTo("false")))
            .body(hasXPath(LOGGING_INCLUDE_COOKIES, equalTo("false")))
            .body(hasXPath(LOGGING_BUCKET, equalTo("")))
            .body(hasXPath(LOGGING_PREFIX, equalTo("")));
    }

    @Test
    void updateDistributionParsesAnExplicitLoggingBlock() {
        // Turning logging on for an existing distribution is an UpdateDistribution, which
        // parses the body through the same path and must not drop the block either.
        String id = postDistribution(distributionConfigBody("origin-logging-update", ""));
        String etag = given()
        .when()
            .get(API + id + "/config")
        .then()
            .statusCode(200)
            .extract().header("ETag");

        given()
            .contentType(ContentType.XML)
            .header("If-Match", etag)
            .body(distributionConfigBody("origin-logging-update", """
                <Logging>
                  <Enabled>true</Enabled>
                  <IncludeCookies>false</IncludeCookies>
                  <Bucket>update-logs.s3.amazonaws.com</Bucket>
                  <Prefix>update/</Prefix>
                </Logging>"""))
        .when()
            .put(API + id + "/config")
        .then()
            .statusCode(200)
            .body(hasXPath(LOGGING_ENABLED, equalTo("true")))
            .body(hasXPath(LOGGING_INCLUDE_COOKIES, equalTo("false")))
            .body(hasXPath(LOGGING_BUCKET, equalTo("update-logs.s3.amazonaws.com")))
            .body(hasXPath(LOGGING_PREFIX, equalTo("update/")));
    }
}
