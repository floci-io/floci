package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * Integration tests for SES V1 Query-protocol ConfigurationSet EventDestination CRUD —
 * {@code CreateConfigurationSetEventDestination} / {@code UpdateConfigurationSetEventDestination} /
 * {@code DeleteConfigurationSetEventDestination}. The DescribeConfigurationSet response is
 * exercised with {@code ConfigurationSetAttributeNames.member.1=eventDestinations} to verify
 * the stored event destination round-trips through the V1 XML response shape.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesConfigurationSetEventDestinationV1IntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/email/aws4_request";
    private static final String CS = "v1-evt-cs";
    private static final String ED = "v1-ed-sns";
    private static final String TOPIC_ARN_A = "arn:aws:sns:us-east-1:000000000000:topic-a";
    private static final String TOPIC_ARN_B = "arn:aws:sns:us-east-1:000000000000:topic-b";

    @Test
    @Order(1)
    void setupConfigurationSet() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "CreateConfigurationSet")
            .formParam("ConfigurationSet.Name", CS)
        .when().post("/")
        .then().statusCode(200);
    }

    @Test
    @Order(2)
    void createConfigurationSetEventDestination() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "CreateConfigurationSetEventDestination")
            .formParam("ConfigurationSetName", CS)
            .formParam("EventDestination.Name", ED)
            .formParam("EventDestination.Enabled", "true")
            .formParam("EventDestination.MatchingEventTypes.member.1", "send")
            .formParam("EventDestination.MatchingEventTypes.member.2", "delivery")
            .formParam("EventDestination.SNSDestination.TopicARN", TOPIC_ARN_A)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("CreateConfigurationSetEventDestinationResponse"));
    }

    @Test
    @Order(3)
    void describeConfigurationSet_withEventDestinationsAttribute_returnsEventDestination() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "DescribeConfigurationSet")
            .formParam("ConfigurationSetName", CS)
            .formParam("ConfigurationSetAttributeNames.member.1", "eventDestinations")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Name>" + ED + "</Name>"))
            .body(containsString("<Enabled>true</Enabled>"))
            .body(containsString("<member>send</member>"))
            .body(containsString("<member>delivery</member>"))
            .body(containsString("<TopicARN>" + TOPIC_ARN_A + "</TopicARN>"));
    }

    @Test
    @Order(4)
    void describeConfigurationSet_withoutEventDestinationsAttribute_omitsEventDestinations() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "DescribeConfigurationSet")
            .formParam("ConfigurationSetName", CS)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Name>" + CS + "</Name>"))
            .body(not(containsString("<EventDestinations>")));
    }

    @Test
    @Order(5)
    void createConfigurationSetEventDestination_duplicateRejected() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "CreateConfigurationSetEventDestination")
            .formParam("ConfigurationSetName", CS)
            .formParam("EventDestination.Name", ED)
            .formParam("EventDestination.Enabled", "true")
            .formParam("EventDestination.MatchingEventTypes.member.1", "send")
            .formParam("EventDestination.SNSDestination.TopicARN", TOPIC_ARN_A)
        .when().post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>AlreadyExists</Code>"));
    }

    @Test
    @Order(6)
    void updateConfigurationSetEventDestination_changesFields() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "UpdateConfigurationSetEventDestination")
            .formParam("ConfigurationSetName", CS)
            .formParam("EventDestination.Name", ED)
            .formParam("EventDestination.Enabled", "false")
            .formParam("EventDestination.MatchingEventTypes.member.1", "bounce")
            .formParam("EventDestination.SNSDestination.TopicARN", TOPIC_ARN_B)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("UpdateConfigurationSetEventDestinationResponse"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "DescribeConfigurationSet")
            .formParam("ConfigurationSetName", CS)
            .formParam("ConfigurationSetAttributeNames.member.1", "eventDestinations")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Enabled>false</Enabled>"))
            .body(containsString("<member>bounce</member>"))
            .body(containsString("<TopicARN>" + TOPIC_ARN_B + "</TopicARN>"));
    }

    @Test
    @Order(7)
    void updateConfigurationSetEventDestination_unknownName_returnsNotFound() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "UpdateConfigurationSetEventDestination")
            .formParam("ConfigurationSetName", CS)
            .formParam("EventDestination.Name", "v1-ed-ghost")
            .formParam("EventDestination.Enabled", "true")
            .formParam("EventDestination.MatchingEventTypes.member.1", "send")
            .formParam("EventDestination.SNSDestination.TopicARN", TOPIC_ARN_A)
        .when().post("/")
        .then()
            .statusCode(404)
            .body(containsString("<Code>NotFoundException</Code>"));
    }

    @Test
    @Order(8)
    void deleteConfigurationSetEventDestination() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "DeleteConfigurationSetEventDestination")
            .formParam("ConfigurationSetName", CS)
            .formParam("EventDestinationName", ED)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("DeleteConfigurationSetEventDestinationResponse"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "DescribeConfigurationSet")
            .formParam("ConfigurationSetName", CS)
            .formParam("ConfigurationSetAttributeNames.member.1", "eventDestinations")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("<Name>" + ED + "</Name>")));
    }

    @Test
    @Order(9)
    void deleteConfigurationSetEventDestination_unknownName_returnsNotFound() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "DeleteConfigurationSetEventDestination")
            .formParam("ConfigurationSetName", CS)
            .formParam("EventDestinationName", "v1-ed-ghost")
        .when().post("/")
        .then()
            .statusCode(404)
            .body(containsString("<Code>NotFoundException</Code>"));
    }

    @Test
    @Order(10)
    void createConfigurationSetEventDestination_routedViaActionFallback_whenAuthHeaderAbsent() {
        // Exercises AwsQueryController.inferServiceFromAction → SES_ACTIONS dispatch
        // when no Authorization header is present (no SigV4 credential scope to resolve).
        String edFallback = "v1-ed-routing-fallback";
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateConfigurationSetEventDestination")
            .formParam("ConfigurationSetName", CS)
            .formParam("EventDestination.Name", edFallback)
            .formParam("EventDestination.Enabled", "true")
            .formParam("EventDestination.MatchingEventTypes.member.1", "send")
            .formParam("EventDestination.SNSDestination.TopicARN", TOPIC_ARN_A)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("CreateConfigurationSetEventDestinationResponse"));

        // Clean up so the cleanup test in @Order(99) doesn't fight a lingering ED.
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "DeleteConfigurationSetEventDestination")
            .formParam("ConfigurationSetName", CS)
            .formParam("EventDestinationName", edFallback)
        .when().post("/")
        .then().statusCode(200);
    }

    @Test
    @Order(11)
    void describeConfigurationSet_eventDestinationsAttribute_returnsEmptyContainerWhenNoDestinations() {
        // After the @Order(8) delete the CS has no event destinations. The attribute was
        // requested, so an empty <EventDestinations/> container should still be returned.
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "DescribeConfigurationSet")
            .formParam("ConfigurationSetName", CS)
            .formParam("ConfigurationSetAttributeNames.member.1", "eventDestinations")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<EventDestinations"))
            .body(not(containsString("<member>")));
    }

    @Test
    @Order(12)
    void createConfigurationSetEventDestination_partialFirehose_rejected() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "CreateConfigurationSetEventDestination")
            .formParam("ConfigurationSetName", CS)
            .formParam("EventDestination.Name", "v1-ed-firehose-bad")
            .formParam("EventDestination.Enabled", "true")
            .formParam("EventDestination.MatchingEventTypes.member.1", "send")
            .formParam("EventDestination.KinesisFirehoseDestination.IAMRoleARN",
                       "arn:aws:iam::000000000000:role/x")
            // DeliveryStreamARN intentionally omitted.
        .when().post("/")
        .then()
            .statusCode(400)
            .body(containsString("KinesisFirehoseDestination requires both IamRoleArn and DeliveryStreamArn"));
    }

    @Test
    @Order(13)
    void createConfigurationSetEventDestination_partialCloudWatchDimension_rejected() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "CreateConfigurationSetEventDestination")
            .formParam("ConfigurationSetName", CS)
            .formParam("EventDestination.Name", "v1-ed-cw-bad")
            .formParam("EventDestination.Enabled", "true")
            .formParam("EventDestination.MatchingEventTypes.member.1", "send")
            .formParam("EventDestination.CloudWatchDestination.DimensionConfigurations.member.1.DimensionName",
                       "MyDim")
            // DimensionValueSource and DefaultDimensionValue intentionally omitted.
        .when().post("/")
        .then()
            .statusCode(400)
            .body(containsString("CloudWatchDestination dimension configurations require"));
    }

    @Test
    @Order(14)
    void createConfigurationSetEventDestination_blankSnsTopicArn_rejected() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "CreateConfigurationSetEventDestination")
            .formParam("ConfigurationSetName", CS)
            .formParam("EventDestination.Name", "v1-ed-sns-blank")
            .formParam("EventDestination.Enabled", "true")
            .formParam("EventDestination.MatchingEventTypes.member.1", "send")
            .formParam("EventDestination.SNSDestination.TopicARN", "")
        .when().post("/")
        .then()
            .statusCode(400)
            .body(containsString("SnsDestination requires a non-blank TopicArn"));
    }

    @Test
    @Order(99)
    void cleanup() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "DeleteConfigurationSet")
            .formParam("ConfigurationSetName", CS)
        .when().post("/")
        .then().statusCode(200);
    }
}
