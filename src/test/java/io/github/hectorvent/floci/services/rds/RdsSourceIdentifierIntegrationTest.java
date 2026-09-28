package io.github.hectorvent.floci.services.rds;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** AddSourceIdentifierToSubscription and RemoveSourceIdentifierFromSubscription. */
@QuarkusTest
class RdsSourceIdentifierIntegrationTest {

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

    private static String subscription(String prefix) {
        String name = prefix + "-" + Long.toString(System.nanoTime(), 36);
        rds("CreateEventSubscription", "SubscriptionName", name, "SnsTopicArn", TOPIC,
                "SourceType", "db-instance", "SourceIds.member.1", "db-one").statusCode(200);
        return name;
    }

    @Test
    void anIdentifierCanBeAddedAndRemoved() {
        String name = subscription("srcid");

        rds("AddSourceIdentifierToSubscription", "SubscriptionName", name,
                "SourceIdentifier", "db-two")
            .statusCode(200)
            .body(containsString("<SourceId>db-one</SourceId>"))
            .body(containsString("<SourceId>db-two</SourceId>"));

        rds("DescribeEventSubscriptions", "SubscriptionName", name)
            .statusCode(200)
            .body(containsString("<SourceId>db-two</SourceId>"));

        rds("RemoveSourceIdentifierFromSubscription", "SubscriptionName", name,
                "SourceIdentifier", "db-two")
            .statusCode(200)
            .body(not(containsString("<SourceId>db-two</SourceId>")))
            .body(containsString("<SourceId>db-one</SourceId>"));

        rds("DescribeEventSubscriptions", "SubscriptionName", name)
            .statusCode(200)
            .body(not(containsString("<SourceId>db-two</SourceId>")));
    }

    /**
     * The model declares only SourceNotFoundFault and SubscriptionNotFoundFault here, so there is
     * no fault that fits a duplicate add.
     */
    @Test
    void addingAnIdentifierTwiceIsANoOp() {
        String name = subscription("dup");

        rds("AddSourceIdentifierToSubscription", "SubscriptionName", name,
                "SourceIdentifier", "db-one").statusCode(200);

        String body = rds("DescribeEventSubscriptions", "SubscriptionName", name)
            .statusCode(200).extract().asString();
        int first = body.indexOf("<SourceId>db-one</SourceId>");
        int second = body.indexOf("<SourceId>db-one</SourceId>", first + 1);
        assertEquals(-1, second,
                "db-one appears once, not twice: " + body);
    }

    @Test
    void removingAnIdentifierTheSubscriptionDoesNotCarryIsSourceNotFound() {
        String name = subscription("absent");

        rds("RemoveSourceIdentifierFromSubscription", "SubscriptionName", name,
                "SourceIdentifier", "db-nine")
            .statusCode(404)
            .body(containsString("SourceNotFound"));
    }

    @Test
    void anUnknownSubscriptionIsSubscriptionNotFound() {
        rds("AddSourceIdentifierToSubscription", "SubscriptionName", "no-such-subscription",
                "SourceIdentifier", "db-one")
            .statusCode(404)
            .body(containsString("SubscriptionNotFound"));

        rds("RemoveSourceIdentifierFromSubscription", "SubscriptionName", "no-such-subscription",
                "SourceIdentifier", "db-one")
            .statusCode(404)
            .body(containsString("SubscriptionNotFound"));
    }

    /**
     * Both members are required by the model, so both answer InvalidParameterValue. A missing name
     * used to reach the lookup and come back as SubscriptionNotFound, which says the subscription
     * does not exist when the request simply did not name one.
     */
    @Test
    void aMissingRequiredMemberIsRefusedTheSameWayOnBothOperations() {
        String name = subscription("nosrc");
        for (String action : new String[]{"AddSourceIdentifierToSubscription",
                "RemoveSourceIdentifierFromSubscription"}) {
            rds(action, "SubscriptionName", name)
                .statusCode(400)
                .body(containsString("InvalidParameterValue"));
            rds(action, "SourceIdentifier", "db-one")
                .statusCode(400)
                .body(containsString("InvalidParameterValue"));
        }
    }
}
