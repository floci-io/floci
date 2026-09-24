package io.github.hectorvent.floci.services.rds;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/** Create, Describe, Modify and Delete for an RDS event notification subscription. */
@QuarkusTest
class RdsEventSubscriptionIntegrationTest {

    private static final String TOPIC = "arn:aws:sns:us-east-1:000000000000:rds-events";

    private static ValidatableResponse rds(String action, String... formParams) {
        var request = given()
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20260615/us-east-1/rds/aws4_request, "
                        + "SignedHeaders=content-type;host, Signature=test")
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", action)
                .formParam("Version", "2014-10-31");
        for (int i = 0; i < formParams.length; i += 2) {
            request = request.formParam(formParams[i], formParams[i + 1]);
        }
        return request.when().post("/").then();
    }

    private static String name(String prefix) {
        return prefix + "-" + Long.toString(System.nanoTime(), 36);
    }

    @Test
    void createDescribeModifyAndDelete() {
        String subscription = name("sub");
        rds("CreateEventSubscription",
                "SubscriptionName", subscription,
                "SnsTopicArn", TOPIC,
                "SourceType", "db-instance",
                "SourceIds.member.1", "db-one",
                "EventCategories.member.1", "availability")
            .statusCode(200)
            .body("CreateEventSubscriptionResponse.CreateEventSubscriptionResult.EventSubscription.CustSubscriptionId",
                    equalTo(subscription))
            .body("CreateEventSubscriptionResponse.CreateEventSubscriptionResult.EventSubscription.Status",
                    equalTo("active"))
            .body("CreateEventSubscriptionResponse.CreateEventSubscriptionResult.EventSubscription.Enabled",
                    equalTo("true"))
            .body("CreateEventSubscriptionResponse.CreateEventSubscriptionResult.EventSubscription.SourceIdsList.SourceId",
                    equalTo("db-one"))
            .body("CreateEventSubscriptionResponse.CreateEventSubscriptionResult.EventSubscription.EventSubscriptionArn",
                    containsString(":es:" + subscription));

        rds("DescribeEventSubscriptions", "SubscriptionName", subscription)
            .statusCode(200)
            .body(containsString("<CustSubscriptionId>" + subscription + "</CustSubscriptionId>"));

        rds("ModifyEventSubscription", "SubscriptionName", subscription, "Enabled", "false")
            .statusCode(200)
            .body("ModifyEventSubscriptionResponse.ModifyEventSubscriptionResult.EventSubscription.Enabled",
                    equalTo("false"))
            // SourceType was not named, so it survives the modify.
            .body("ModifyEventSubscriptionResponse.ModifyEventSubscriptionResult.EventSubscription.SourceType",
                    equalTo("db-instance"));

        rds("DeleteEventSubscription", "SubscriptionName", subscription).statusCode(200);
        rds("DescribeEventSubscriptions", "SubscriptionName", subscription).statusCode(404);
    }

    @Test
    void sourceIdsWithoutASourceTypeIsRefused() {
        rds("CreateEventSubscription",
                "SubscriptionName", name("nosrctype"),
                "SnsTopicArn", TOPIC,
                "SourceIds.member.1", "db-one")
            .statusCode(400)
            .body(containsString("InvalidParameterCombination"));
    }

    @Test
    void anUnmodelledSourceTypeIsRefused() {
        rds("CreateEventSubscription",
                "SubscriptionName", name("badtype"),
                "SnsTopicArn", TOPIC,
                "SourceType", "db-widget")
            .statusCode(400)
            .body(containsString("InvalidParameterValue"));
    }

    @Test
    void aMissingTopicArnIsRefusedAndASecondCreateClashes() {
        rds("CreateEventSubscription", "SubscriptionName", name("notopic"))
            .statusCode(404)
            .body(containsString("SNSTopicArnNotFound"));

        String subscription = name("dup");
        rds("CreateEventSubscription", "SubscriptionName", subscription, "SnsTopicArn", TOPIC)
            .statusCode(200);
        rds("CreateEventSubscription", "SubscriptionName", subscription, "SnsTopicArn", TOPIC)
            .statusCode(400)
            .body(containsString("SubscriptionAlreadyExist"));
    }

    @Test
    void describeWithNoNameListsThemAndAnUnknownNameIsNotFound() {
        String subscription = name("listed");
        rds("CreateEventSubscription", "SubscriptionName", subscription, "SnsTopicArn", TOPIC)
            .statusCode(200);

        rds("DescribeEventSubscriptions")
            .statusCode(200)
            .body(containsString("<CustSubscriptionId>" + subscription + "</CustSubscriptionId>"));

        rds("DescribeEventSubscriptions", "SubscriptionName", "no-such-subscription")
            .statusCode(404)
            .body(containsString("SubscriptionNotFound"));
    }

    @Test
    void anInactiveSubscriptionIsCreatedButNotActive() {
        String subscription = name("off");
        rds("CreateEventSubscription",
                "SubscriptionName", subscription, "SnsTopicArn", TOPIC, "Enabled", "false")
            .statusCode(200)
            .body("CreateEventSubscriptionResponse.CreateEventSubscriptionResult.EventSubscription.Enabled",
                    equalTo("false"))
            .body(not(containsString("<Enabled>true</Enabled>")));
    }
}
