package io.floci.aws.services.ses;

import static io.restassured.RestAssured.given;

/** Creates the SNS topics that SES settings name, since SES checks a topic when it is set. */
final class SnsTopicFixture {

    private SnsTopicFixture() {
    }

    static String create(String region, String name) {
        return given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260101/" + region + "/sns/aws4_request")
                .formParam("Action", "CreateTopic")
                .formParam("Name", name)
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .extract().xmlPath().getString("CreateTopicResponse.CreateTopicResult.TopicArn");
    }
}
