package io.floci.aws.services.cloudformation;

import static io.restassured.RestAssured.given;

/**
 * What the CloudFormation integration tests share to write an SSM parameter and read back one a stack wrote.
 */
final class SsmParameterTestSupport {

    private static final String SSM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ssm/aws4_request";

    private SsmParameterTestSupport() {
    }

    static void putParameter(String name) {
        given().contentType("application/x-amz-json-1.1").header("Authorization", SSM_AUTH)
                .header("X-Amz-Target", "AmazonSSM.PutParameter")
                .body("{\"Name\":\"" + name + "\",\"Value\":\"foreign\",\"Type\":\"String\"}")
                .when().post("/").then().statusCode(200);
    }

    static String parameterValue(String name) {
        return given()
                .contentType("application/x-amz-json-1.1")
                .header("Authorization", SSM_AUTH)
                .header("X-Amz-Target", "AmazonSSM.GetParameter")
                .body("{\"Name\":\"" + name + "\"}")
                .when().post("/").then().statusCode(200)
                .extract().jsonPath().getString("Parameter.Value");
    }
}
