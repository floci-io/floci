package io.floci.aws.services.ecs;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;

/**
 * Capacity providers and account settings are regional: a provider name is taken once per region,
 * and "account settings are set on a per-Region basis" (botocore ecs PutAccountSetting).
 */
@QuarkusTest
class EcsCapacityProviderRegionIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String TARGET_PREFIX = "AmazonEC2ContainerServiceV20141113.";
    private static final String HOME = "eu-west-1";
    private static final String OTHER = "us-west-2";
    private static final String ABSENT = "ap-southeast-1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static RequestSpecification ecs(String region, String action) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260215/" + region
                        + "/ecs/aws4_request, SignedHeaders=host, Signature=abc")
                .header("X-Amz-Target", TARGET_PREFIX + action);
    }

    private static String createProvider(String region, String name) {
        return ecs(region, "CreateCapacityProvider")
            .body("""
                {"name": "%s", "autoScalingGroupProvider": {"autoScalingGroupArn":
                 "arn:aws:autoscaling:%s:000000000000:autoScalingGroup:x:autoScalingGroupName/asg-%s"}}
                """.formatted(name, region, name))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("capacityProvider.capacityProviderArn", containsString(":" + region + ":"))
            .extract().path("capacityProvider.capacityProviderArn");
    }

    @Test
    void capacityProvidersStayInTheirRegion() {
        String name = "region-cp-" + UUID.randomUUID().toString().substring(0, 8);
        String homeArn = createProvider(HOME, name);
        String otherArn = createProvider(OTHER, name);

        ecs(HOME, "DescribeCapacityProviders")
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("capacityProviders.capacityProviderArn", hasItems(homeArn))
            .body("capacityProviders.capacityProviderArn", not(hasItems(otherArn)))
            .body("capacityProviders.name", hasItems("FARGATE", "FARGATE_SPOT"));
        ecs(OTHER, "DescribeCapacityProviders")
            .body("{\"capacityProviders\": [\"" + name + "\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("capacityProviders.capacityProviderArn", contains(otherArn));
        ecs(ABSENT, "DescribeCapacityProviders")
            .body("{\"capacityProviders\": [\"" + name + "\", \"FARGATE\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("capacityProviders.name", contains("FARGATE"))
            .body("failures[0].reason", equalTo("MISSING"));
        ecs(ABSENT, "DeleteCapacityProvider")
            .body("{\"capacityProvider\": \"" + name + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400);

        ecs(HOME, "DeleteCapacityProvider")
            .body("{\"capacityProvider\": \"" + name + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("capacityProvider.capacityProviderArn", equalTo(homeArn));
        ecs(OTHER, "DescribeCapacityProviders")
            .body("{\"capacityProviders\": [\"" + name + "\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("capacityProviders.capacityProviderArn", contains(otherArn));
        ecs(OTHER, "DeleteCapacityProvider")
            .body("{\"capacityProvider\": \"" + otherArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    void accountSettingsStayInTheirRegion() {
        String principal = "arn:aws:iam::000000000000:role/region-setting-"
                + UUID.randomUUID().toString().substring(0, 8);
        ecs(HOME, "PutAccountSetting")
            .body("{\"name\": \"awsvpcTrunking\", \"value\": \"enabled\", \"principalArn\": \"" + principal + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        ecs(HOME, "ListAccountSettings")
            .body("{\"name\": \"awsvpcTrunking\", \"principalArn\": \"" + principal + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("settings.value", contains("enabled"));
        ecs(OTHER, "ListAccountSettings")
            .body("{\"name\": \"awsvpcTrunking\", \"principalArn\": \"" + principal + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("settings", empty());
        ecs(OTHER, "ListAccountSettings")
            .body("{\"name\": \"awsvpcTrunking\", \"principalArn\": \"" + principal
                    + "\", \"effectiveSettings\": true}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("settings.value", contains("disabled"));

        ecs(HOME, "DeleteAccountSetting")
            .body("{\"name\": \"awsvpcTrunking\", \"principalArn\": \"" + principal + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("setting.value", equalTo("enabled"));
    }
}
