package io.github.hectorvent.floci.services.rds;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Create, Describe, Modify and Delete for an RDS event notification subscription. */
@QuarkusTest
class RdsEventSubscriptionIntegrationTest {

    private static final String TOPIC = "arn:aws:sns:us-east-1:000000000000:rds-events";

    private static ValidatableResponse rds(String action, String... formParams) {
        RequestSpecification request = given()
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
    void aRejectedModifyChangesNothing() {
        String subscription = name("atomic");
        rds("CreateEventSubscription", "SubscriptionName", subscription, "SnsTopicArn", TOPIC,
                "SourceType", "db-instance").statusCode(200);

        // Valid SnsTopicArn, invalid SourceType. The topic must not survive the 400.
        rds("ModifyEventSubscription", "SubscriptionName", subscription,
                "SnsTopicArn", "arn:aws:sns:us-east-1:000000000000:other", "SourceType", "db-widget")
            .statusCode(400);

        rds("DescribeEventSubscriptions", "SubscriptionName", subscription)
            .statusCode(200)
            .body(containsString("<SnsTopicArn>" + TOPIC + "</SnsTopicArn>"))
            .body(not(containsString("rds-events-other")));
    }

    @Test
    void tagsGivenAtCreateAreReadableThroughListTagsForResource() {
        String subscription = name("tagged");
        String arn = rds("CreateEventSubscription", "SubscriptionName", subscription,
                "SnsTopicArn", TOPIC, "Tags.Tag.1.Key", "team", "Tags.Tag.1.Value", "platform")
            .statusCode(200)
            .extract().path("CreateEventSubscriptionResponse.CreateEventSubscriptionResult"
                    + ".EventSubscription.EventSubscriptionArn");

        rds("ListTagsForResource", "ResourceName", arn)
            .statusCode(200)
            .body(containsString("<Key>team</Key>"))
            .body(containsString("<Value>platform</Value>"));
    }

    @Test
    void describeHonoursMaxRecordsAndMarker() {
        // The model bounds MaxRecords at 20, so a meaningful page needs more than 20 rows.
        String prefix = "page-" + Long.toString(System.nanoTime(), 36);
        for (int i = 0; i < 21; i++) {
            rds("CreateEventSubscription", "SubscriptionName", String.format("%s-%02d", prefix, i),
                    "SnsTopicArn", TOPIC).statusCode(200);
        }

        String marker = rds("DescribeEventSubscriptions", "MaxRecords", "20")
            .statusCode(200)
            .extract().path("DescribeEventSubscriptionsResponse"
                    + ".DescribeEventSubscriptionsResult.Marker");
        assertNotNull(marker, "a bounded page short of the end carries a Marker");

        rds("DescribeEventSubscriptions", "MaxRecords", "20", "Marker", marker)
            .statusCode(200)
            .body(not(containsString("<CustSubscriptionId>" + marker + "</CustSubscriptionId>")));
    }

    @Test
    void anOutOfRangeMaxRecordsIsRefused() {
        // 0 used to build an empty page and then read its last element.
        rds("DescribeEventSubscriptions", "MaxRecords", "0").statusCode(400)
            .body(containsString("InvalidParameterValue"));
        rds("DescribeEventSubscriptions", "MaxRecords", "-5").statusCode(400);
        rds("DescribeEventSubscriptions", "MaxRecords", "19").statusCode(400);
        rds("DescribeEventSubscriptions", "MaxRecords", "101").statusCode(400);
        rds("DescribeEventSubscriptions", "MaxRecords", "20").statusCode(200);
    }

    @Test
    void anOutOfRangeMaxRecordsIsRefusedEvenWhenASubscriptionIsNamed() {
        String subscription = name("named");
        rds("CreateEventSubscription", "SubscriptionName", subscription, "SnsTopicArn", TOPIC)
            .statusCode(200);

        // The named-subscription read used to return before the page size was looked at.
        rds("DescribeEventSubscriptions", "SubscriptionName", subscription, "MaxRecords", "0")
            .statusCode(400)
            .body(containsString("InvalidParameterValue"));
        rds("DescribeEventSubscriptions", "SubscriptionName", subscription, "MaxRecords", "20")
            .statusCode(200);
    }

    @Test
    void aMarkerWhoseSubscriptionIsGoneDoesNotRestartTheWalk() {
        String prefix = "gone-" + Long.toString(System.nanoTime(), 36);
        for (int i = 0; i < 21; i++) {
            rds("CreateEventSubscription", "SubscriptionName", String.format("%s-%02d", prefix, i),
                    "SnsTopicArn", TOPIC).statusCode(200);
        }
        String marker = rds("DescribeEventSubscriptions", "MaxRecords", "20")
            .statusCode(200)
            .extract().path("DescribeEventSubscriptionsResponse"
                    + ".DescribeEventSubscriptionsResult.Marker");
        assertNotNull(marker);

        rds("DeleteEventSubscription", "SubscriptionName", marker).statusCode(200);

        // Resuming from a name that no longer exists must not hand back the first page again.
        rds("DescribeEventSubscriptions", "MaxRecords", "20", "Marker", marker)
            .statusCode(200)
            .body(not(containsString("<CustSubscriptionId>" + prefix + "-00</CustSubscriptionId>")));
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
