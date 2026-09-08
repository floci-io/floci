package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * VpcConfig, SnapStart and LoggingConfig must survive CreateFunction and
 * UpdateFunctionConfiguration and read back from GetFunctionConfiguration / GetFunction.
 * Terraform refreshes all three on every plan, so a field accepted and then dropped is a
 * permanent non-converging diff rather than a cosmetic omission.
 *
 * <p>The request and response shapes deliberately differ: VpcConfigResponse adds VpcId and
 * SnapStartResponse adds OptimizationStatus, neither of which the caller can send.
 */
@QuarkusTest
class LambdaVpcSnapStartLoggingIntegrationTest {

    private static final String BASE_PATH = "/2015-03-31";
    private static final String REGION = "us-east-1";
    private static final String SECURITY_GROUP_ID = "sg-0123456789abcdef0";

    @Inject
    Ec2Service ec2Service;

    private String subnetId;
    private String expectedVpcId;

    @BeforeEach
    void resolveSubnet() {
        Subnet subnet = ec2Service.describeSubnets(REGION, List.of(), Map.of()).get(0);
        subnetId = subnet.getSubnetId();
        expectedVpcId = subnet.getVpcId();
    }

    private void createFunction(String name, String extraJson) {
        createFunction(name, "nodejs20.x", extraJson);
    }

    /**
     * SnapStart is only available on a subset of managed runtimes (Java 11+, Python 3.12+,
     * .NET 8+); {@code nodejs20.x} is not among them. Tests that exercise SnapStart use this
     * overload with a supported runtime so they don't lock in a create AWS would reject.
     */
    private void createFunction(String name, String runtime, String extraJson) {
        given()
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "%s",
                    "Runtime": "%s",
                    "Role": "arn:aws:iam::000000000000:role/lambda-role",
                    "Handler": "index.handler"%s
                }
                """.formatted(name, runtime, extraJson))
        .when()
            .post(BASE_PATH + "/functions")
        .then()
            .statusCode(201);
    }

    private String vpcConfigJson() {
        return """
            ,
                "VpcConfig": {
                    "SubnetIds": ["%s"],
                    "SecurityGroupIds": ["%s"]
                }""".formatted(subnetId, SECURITY_GROUP_ID);
    }

    @Test
    void createFunctionReturnsVpcConfigWithResolvedVpcId() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "vpc-create-fn",
                    "Runtime": "nodejs20.x",
                    "Role": "arn:aws:iam::000000000000:role/lambda-role",
                    "Handler": "index.handler"%s
                }
                """.formatted(vpcConfigJson()))
        .when()
            .post(BASE_PATH + "/functions")
        .then()
            .statusCode(201)
            .body("VpcConfig.SubnetIds", contains(subnetId))
            .body("VpcConfig.SecurityGroupIds", contains(SECURITY_GROUP_ID))
            .body("VpcConfig.VpcId", equalTo(expectedVpcId))
            .body("VpcConfig.Ipv6AllowedForDualStack", equalTo(false));
    }

    @Test
    void getFunctionConfigurationReturnsVpcConfig() {
        createFunction("vpc-get-config-fn", vpcConfigJson());

        given()
        .when()
            .get(BASE_PATH + "/functions/vpc-get-config-fn/configuration")
        .then()
            .statusCode(200)
            .body("VpcConfig.SubnetIds", contains(subnetId))
            .body("VpcConfig.SecurityGroupIds", contains(SECURITY_GROUP_ID))
            .body("VpcConfig.VpcId", equalTo(expectedVpcId));
    }

    @Test
    void getFunctionReturnsVpcConfig() {
        createFunction("vpc-get-fn", vpcConfigJson());

        given()
        .when()
            .get(BASE_PATH + "/functions/vpc-get-fn")
        .then()
            .statusCode(200)
            .body("Configuration.VpcConfig.SubnetIds", contains(subnetId))
            .body("Configuration.VpcConfig.SecurityGroupIds", contains(SECURITY_GROUP_ID))
            .body("Configuration.VpcConfig.VpcId", equalTo(expectedVpcId));
    }

    @Test
    void updateFunctionConfigurationAttachesVpcAndRoundTrips() {
        createFunction("vpc-update-fn", "");

        given()
            .contentType("application/json")
            .body("""
                {
                    "VpcConfig": {
                        "SubnetIds": ["%s"],
                        "SecurityGroupIds": ["%s"]
                    }
                }
                """.formatted(subnetId, SECURITY_GROUP_ID))
        .when()
            .put(BASE_PATH + "/functions/vpc-update-fn/configuration")
        .then()
            .statusCode(200)
            .body("VpcConfig.SubnetIds", contains(subnetId))
            .body("VpcConfig.VpcId", equalTo(expectedVpcId));

        given()
        .when()
            .get(BASE_PATH + "/functions/vpc-update-fn/configuration")
        .then()
            .statusCode(200)
            .body("VpcConfig.SubnetIds", contains(subnetId))
            .body("VpcConfig.SecurityGroupIds", contains(SECURITY_GROUP_ID))
            .body("VpcConfig.VpcId", equalTo(expectedVpcId));
    }

    @Test
    void vpcConfigIsAbsentWhenTheFunctionIsNotAttached() {
        createFunction("vpc-absent-fn", "");

        given()
        .when()
            .get(BASE_PATH + "/functions/vpc-absent-fn/configuration")
        .then()
            .statusCode(200)
            .body("VpcConfig", nullValue());
    }

    @Test
    void unknownSubnetStillRoundTripsWithoutVpcId() {
        createFunction("vpc-unmanaged-subnet-fn", """
            ,
                "VpcConfig": {
                    "SubnetIds": ["subnet-ffffffffffffffff0"],
                    "SecurityGroupIds": ["%s"]
                }""".formatted(SECURITY_GROUP_ID));

        given()
        .when()
            .get(BASE_PATH + "/functions/vpc-unmanaged-subnet-fn/configuration")
        .then()
            .statusCode(200)
            .body("VpcConfig.SubnetIds", contains("subnet-ffffffffffffffff0"))
            .body("VpcConfig.VpcId", nullValue());
    }

    @Test
    void snapStartAndLoggingConfigDefaultsArePopulatedWhenUnset() {
        createFunction("defaults-fn", "");

        given()
        .when()
            .get(BASE_PATH + "/functions/defaults-fn/configuration")
        .then()
            .statusCode(200)
            .body("SnapStart.ApplyOn", equalTo("None"))
            .body("SnapStart.OptimizationStatus", equalTo("Off"))
            .body("LoggingConfig.LogFormat", equalTo("Text"))
            .body("LoggingConfig.LogGroup", equalTo("/aws/lambda/defaults-fn"))
            .body("LoggingConfig.ApplicationLogLevel", nullValue())
            .body("RuntimeVersionConfig.RuntimeVersionArn", startsWith("arn:aws:lambda:us-east-1::runtime:"));
    }

    @Test
    void snapStartRoundTripsAndOptimizationStatusIsResponseOnly() {
        createFunction("snapstart-fn", "java21", """
            ,
                "SnapStart": {"ApplyOn": "PublishedVersions"}""");

        given()
        .when()
            .get(BASE_PATH + "/functions/snapstart-fn/configuration")
        .then()
            .statusCode(200)
            .body("SnapStart.ApplyOn", equalTo("PublishedVersions"))
            .body("SnapStart.OptimizationStatus", equalTo("Off"));

        given()
            .contentType("application/json")
            .body("{}")
        .when()
            .post(BASE_PATH + "/functions/snapstart-fn/versions")
        .then()
            .statusCode(201)
            .body("SnapStart.ApplyOn", equalTo("PublishedVersions"))
            .body("SnapStart.OptimizationStatus", equalTo("On"));
    }

    @Test
    void updateFunctionConfigurationRoundTripsSnapStart() {
        createFunction("snapstart-update-fn", "java21", "");

        given()
            .contentType("application/json")
            .body("{\"SnapStart\": {\"ApplyOn\": \"PublishedVersions\"}}")
        .when()
            .put(BASE_PATH + "/functions/snapstart-update-fn/configuration")
        .then()
            .statusCode(200)
            .body("SnapStart.ApplyOn", equalTo("PublishedVersions"));

        given()
        .when()
            .get(BASE_PATH + "/functions/snapstart-update-fn/configuration")
        .then()
            .statusCode(200)
            .body("SnapStart.ApplyOn", equalTo("PublishedVersions"));
    }

    @Test
    void snapStartRejectsValueOutsideTheEnum() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "snapstart-invalid-fn",
                    "Runtime": "nodejs20.x",
                    "Role": "arn:aws:iam::000000000000:role/lambda-role",
                    "Handler": "index.handler",
                    "SnapStart": {"ApplyOn": "Always"}
                }
                """)
        .when()
            .post(BASE_PATH + "/functions")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("1 validation error detected: Value 'Always' at 'snapStart.applyOn' "
                    + "failed to satisfy constraint: Member must satisfy enum value set: "
                    + "[PublishedVersions, None]"));
    }

    @Test
    void jsonLoggingConfigRoundTripsWithLogLevels() {
        createFunction("logging-json-fn", """
            ,
                "LoggingConfig": {
                    "LogFormat": "JSON",
                    "ApplicationLogLevel": "DEBUG",
                    "SystemLogLevel": "WARN",
                    "LogGroup": "/custom/log/group"
                }""");

        given()
        .when()
            .get(BASE_PATH + "/functions/logging-json-fn/configuration")
        .then()
            .statusCode(200)
            .body("LoggingConfig.LogFormat", equalTo("JSON"))
            .body("LoggingConfig.ApplicationLogLevel", equalTo("DEBUG"))
            .body("LoggingConfig.SystemLogLevel", equalTo("WARN"))
            .body("LoggingConfig.LogGroup", equalTo("/custom/log/group"));
    }

    @Test
    void jsonLoggingConfigDefaultsBothLogLevelsToInfo() {
        createFunction("logging-json-default-fn", """
            ,
                "LoggingConfig": {"LogFormat": "JSON"}""");

        given()
        .when()
            .get(BASE_PATH + "/functions/logging-json-default-fn/configuration")
        .then()
            .statusCode(200)
            .body("LoggingConfig.LogFormat", equalTo("JSON"))
            .body("LoggingConfig.ApplicationLogLevel", equalTo("INFO"))
            .body("LoggingConfig.SystemLogLevel", equalTo("INFO"))
            .body("LoggingConfig.LogGroup", equalTo("/aws/lambda/logging-json-default-fn"));
    }

    @Test
    void updateFunctionConfigurationReplacesLoggingConfigWholesale() {
        createFunction("logging-update-fn", """
            ,
                "LoggingConfig": {
                    "LogFormat": "JSON",
                    "ApplicationLogLevel": "DEBUG",
                    "LogGroup": "/custom/log/group"
                }""");

        given()
            .contentType("application/json")
            .body("{\"LoggingConfig\": {\"LogFormat\": \"Text\"}}")
        .when()
            .put(BASE_PATH + "/functions/logging-update-fn/configuration")
        .then()
            .statusCode(200)
            .body("LoggingConfig.LogFormat", equalTo("Text"))
            .body("LoggingConfig.LogGroup", equalTo("/aws/lambda/logging-update-fn"));

        given()
        .when()
            .get(BASE_PATH + "/functions/logging-update-fn/configuration")
        .then()
            .statusCode(200)
            .body("LoggingConfig.LogFormat", equalTo("Text"))
            .body("LoggingConfig.ApplicationLogLevel", nullValue())
            .body("LoggingConfig.LogGroup", equalTo("/aws/lambda/logging-update-fn"));
    }

    @Test
    void loggingConfigRejectsLogFormatOutsideTheEnum() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "logging-invalid-fn",
                    "Runtime": "nodejs20.x",
                    "Role": "arn:aws:iam::000000000000:role/lambda-role",
                    "Handler": "index.handler",
                    "LoggingConfig": {"LogFormat": "YAML"}
                }
                """)
        .when()
            .post(BASE_PATH + "/functions")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("1 validation error detected: Value 'YAML' at 'loggingConfig.logFormat' "
                    + "failed to satisfy constraint: Member must satisfy enum value set: [JSON, Text]"));
    }

    @Test
    void loggingConfigRejectsApplicationLogLevelOutsideTheEnum() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "logging-invalid-app-level-fn",
                    "Runtime": "nodejs20.x",
                    "Role": "arn:aws:iam::000000000000:role/lambda-role",
                    "Handler": "index.handler",
                    "LoggingConfig": {"LogFormat": "JSON", "ApplicationLogLevel": "VERBOSE"}
                }
                """)
        .when()
            .post(BASE_PATH + "/functions")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("1 validation error detected: Value 'VERBOSE' at "
                    + "'loggingConfig.applicationLogLevel' failed to satisfy constraint: Member must satisfy "
                    + "enum value set: [TRACE, DEBUG, INFO, WARN, ERROR, FATAL]"));
    }

    @Test
    void loggingConfigRejectsSystemLogLevelOutsideTheEnum() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "logging-invalid-system-level-fn",
                    "Runtime": "nodejs20.x",
                    "Role": "arn:aws:iam::000000000000:role/lambda-role",
                    "Handler": "index.handler",
                    "LoggingConfig": {"LogFormat": "JSON", "SystemLogLevel": "TRACE"}
                }
                """)
        .when()
            .post(BASE_PATH + "/functions")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("1 validation error detected: Value 'TRACE' at "
                    + "'loggingConfig.systemLogLevel' failed to satisfy constraint: Member must satisfy "
                    + "enum value set: [DEBUG, INFO, WARN]"));
    }

    @Test
    void loggingConfigRejectsLogGroupOutsideTheDocumentedPattern() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "logging-invalid-group-fn",
                    "Runtime": "nodejs20.x",
                    "Role": "arn:aws:iam::000000000000:role/lambda-role",
                    "Handler": "index.handler",
                    "LoggingConfig": {"LogGroup": "my log group"}
                }
                """)
        .when()
            .post(BASE_PATH + "/functions")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    /**
     * The service model gives LogGroup a 1-character minimum, so an empty string is a length
     * violation on paper. Floci reads it as "not supplied" instead: botocore enforces that
     * minimum client side, so an empty LogGroup never reaches the wire from an SDK caller and
     * nobody has observed what AWS answers to one. This pins the leniency rather than a 400 we
     * would only have inferred.
     */
    @Test
    void blankLogGroupIsReadAsNotSuppliedRatherThanRejected() {
        createFunction("logging-blank-group-fn", """
            ,
                "LoggingConfig": {"LogGroup": ""}""");

        given()
        .when()
            .get(BASE_PATH + "/functions/logging-blank-group-fn/configuration")
        .then()
            .statusCode(200)
            .body("LoggingConfig.LogGroup", equalTo("/aws/lambda/logging-blank-group-fn"));
    }

    @Test
    void whitespaceOnlyLogGroupIsReadAsNotSuppliedRatherThanRejected() {
        // The guard is isBlank(), not isEmpty(), so a whitespace-only value takes the same
        // deliberate not-supplied path as "". Pinned separately because the javadoc claims
        // both and an empty-string-only test would let isBlank() be narrowed to isEmpty()
        // without anything going red.
        createFunction("logging-ws-group-fn", """
            ,
                "LoggingConfig": {"LogGroup": "   "}""");

        given()
        .when()
            .get(BASE_PATH + "/functions/logging-ws-group-fn/configuration")
        .then()
            .statusCode(200)
            .body("LoggingConfig.LogGroup", equalTo("/aws/lambda/logging-ws-group-fn"));
    }

    @Test
    void loggingConfigRejectsLogGroupLongerThanFiveHundredTwelveCharacters() {
        String tooLong = "a".repeat(513);

        given()
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "logging-invalid-long-group-fn",
                    "Runtime": "nodejs20.x",
                    "Role": "arn:aws:iam::000000000000:role/lambda-role",
                    "Handler": "index.handler",
                    "LoggingConfig": {"LogGroup": "%s"}
                }
                """.formatted(tooLong))
        .when()
            .post(BASE_PATH + "/functions")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    void textFormatLogLevelsAreAcceptedButNeverStoredOrReturned() {
        createFunction("logging-text-levels-fn", """
            ,
                "LoggingConfig": {
                    "LogFormat": "Text",
                    "ApplicationLogLevel": "DEBUG",
                    "SystemLogLevel": "WARN"
                }""");

        given()
        .when()
            .get(BASE_PATH + "/functions/logging-text-levels-fn/configuration")
        .then()
            .statusCode(200)
            .body("LoggingConfig.LogFormat", equalTo("Text"))
            .body("LoggingConfig.ApplicationLogLevel", nullValue())
            .body("LoggingConfig.SystemLogLevel", nullValue());
    }

    @Test
    void updateFunctionConfigurationDetachesVpcConfigAndClearsVpcId() {
        createFunction("vpc-detach-fn", vpcConfigJson());

        given()
        .when()
            .get(BASE_PATH + "/functions/vpc-detach-fn/configuration")
        .then()
            .statusCode(200)
            .body("VpcConfig.VpcId", equalTo(expectedVpcId));

        given()
            .contentType("application/json")
            .body("""
                {
                    "VpcConfig": {
                        "SubnetIds": [],
                        "SecurityGroupIds": []
                    }
                }
                """)
        .when()
            .put(BASE_PATH + "/functions/vpc-detach-fn/configuration")
        .then()
            .statusCode(200)
            .body("VpcConfig", nullValue());

        given()
        .when()
            .get(BASE_PATH + "/functions/vpc-detach-fn/configuration")
        .then()
            .statusCode(200)
            .body("VpcConfig", nullValue());
    }

    @Test
    void publishedVersionCarriesVpcAndLoggingConfig() {
        createFunction("version-carry-fn", vpcConfigJson() + """
            ,
                "LoggingConfig": {"LogFormat": "JSON", "LogGroup": "/custom/version/group"}""");

        given()
            .contentType("application/json")
            .body("{}")
        .when()
            .post(BASE_PATH + "/functions/version-carry-fn/versions")
        .then()
            .statusCode(201)
            .body("Version", notNullValue())
            .body("VpcConfig.SubnetIds", contains(subnetId))
            .body("VpcConfig.VpcId", equalTo(expectedVpcId))
            .body("LoggingConfig.LogFormat", equalTo("JSON"))
            .body("LoggingConfig.LogGroup", equalTo("/custom/version/group"));
    }
}
