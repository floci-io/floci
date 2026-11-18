package io.floci.aws.services.cloudformation;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.CfnStackWaits;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;

import static io.floci.aws.services.cloudformation.CfnStackTestSupport.physicalId;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * An AWS::ApiGateway::Authorizer updated through a stack: a change that needs no replacement keeps the
 * authorizer's id and applies on a later retry after a rollback, and a changed RestApiId replaces it.
 */
@QuarkusTest
class ApiGatewayAuthorizerCfnUpdateIntegrationTest {

    private static final String SETTINGS = "\"Name\":\"n1\",\"AuthorizerResultTtlInSeconds\":60";
    private static final String CHANGED = "\"Name\":\"n2\"";

    private static CloudFormationClient client() {
        return AwsTestClients.create(CloudFormationClient.builder(), "test", Region.US_EAST_1);
    }

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void anInPlaceUpdateKeepsTheAuthorizerAndARollbackRestoresIt() {
        String stack = "apigw-cfn-authorizer-inplace-it";
        CloudFormationClient cf = client();
        try {
            CfnStackWaits.createStack(cf, stack, template("ApiA", SETTINGS, ""), "CREATE_COMPLETE");
            String apiId = physicalId(cf, stack, "ApiA");
            String authorizerId = physicalId(cf, stack, "Auth");

            CfnStackWaits.updateStack(cf, stack,
                    template("ApiA", CHANGED, CfnStackWaits.failingResourceAfter("Auth")),
                    "UPDATE_ROLLBACK_COMPLETE");
            assertEquals(authorizerId, physicalId(cf, stack, "Auth"));
            given().when().get("/restapis/" + apiId + "/authorizers/" + authorizerId).then().statusCode(200)
                    .body("name", equalTo("n1")).body("authorizerResultTtlInSeconds", equalTo(60));

            CfnStackWaits.updateStack(cf, stack, template("ApiA", CHANGED, ""), "UPDATE_COMPLETE");
            assertEquals(authorizerId, physicalId(cf, stack, "Auth"));
            given().when().get("/restapis/" + apiId + "/authorizers").then().statusCode(200)
                    .body("item", hasSize(1));
            given().when().get("/restapis/" + apiId + "/authorizers/" + authorizerId).then().statusCode(200)
                    .body("name", equalTo("n2")).body("authorizerResultTtlInSeconds", equalTo(300));
        } finally {
            CfnStackWaits.deleteStack(cf, stack);
            cf.close();
        }
    }

    @Test
    void aChangedRestApiIdReplacesTheAuthorizer() {
        String stack = "apigw-cfn-authorizer-replace-it";
        CloudFormationClient cf = client();
        try {
            CfnStackWaits.createStack(cf, stack, template("ApiA", SETTINGS, ""), "CREATE_COMPLETE");
            String apiA = physicalId(cf, stack, "ApiA");
            String apiB = physicalId(cf, stack, "ApiB");
            String authorizerId = physicalId(cf, stack, "Auth");

            CfnStackWaits.updateStack(cf, stack, template("ApiB", SETTINGS, ""), "UPDATE_COMPLETE");
            assertNotEquals(authorizerId, physicalId(cf, stack, "Auth"));
            given().when().get("/restapis/" + apiA + "/authorizers").then().statusCode(200)
                    .body("item", hasSize(0));
            given().when().get("/restapis/" + apiB + "/authorizers").then().statusCode(200)
                    .body("item", hasSize(1));
        } finally {
            CfnStackWaits.deleteStack(cf, stack);
            cf.close();
        }
    }

    /** Two REST APIs and a TOKEN authorizer on {@code apiRef}; {@code later} is appended to the resources. */
    private static String template(String apiRef, String settings, String later) {
        return """
                {"Resources":{
                  "ApiA":{"Type":"AWS::ApiGateway::RestApi","Properties":{"Name":"cfn-authorizer-a"}},
                  "ApiB":{"Type":"AWS::ApiGateway::RestApi","Properties":{"Name":"cfn-authorizer-b"}},
                  "Auth":{"Type":"AWS::ApiGateway::Authorizer","Properties":{
                    "RestApiId":{"Ref":"%s"},"Type":"TOKEN","AuthorizerUri":"u1",
                    "IdentitySource":"method.request.header.Auth",%s}}%s}}
                """.formatted(apiRef, settings, later);
    }
}
