package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class S3EventBridgeNotificationValidationIntegrationTest {

    @Test
    void namespacedEventBridgeConfigurationIsAccepted() {
        String bucket = createBucket("namespaced");

        given()
                .contentType("application/xml")
                .body("""
                        <s3:NotificationConfiguration xmlns:s3="http://s3.amazonaws.com/doc/2006-03-01/">
                            <s3:EventBridgeConfiguration/>
                        </s3:NotificationConfiguration>
                        """)
                .when()
                .put("/" + bucket + "?notification")
                .then()
                .statusCode(200);

        given()
                .when()
                .get("/" + bucket + "?notification")
                .then()
                .statusCode(200)
                .body(containsString("<EventBridgeConfiguration"));
    }

    @Test
    void malformedNotificationConfigurationIsRejected() {
        String bucket = createBucket("malformed");

        putNotification(bucket, "<NotificationConfiguration><EventBridgeConfiguration></NotificationConfiguration>")
                .statusCode(400)
                .body(containsString("<Code>MalformedXML</Code>"));
    }

    @Test
    void eventBridgeTextInCommentDoesNotEnableEventBridge() {
        String bucket = createBucket("comment");

        putNotification(bucket, """
                <NotificationConfiguration>
                    <!-- <EventBridgeConfiguration/> -->
                </NotificationConfiguration>
        """).statusCode(200);

        getNotification(bucket)
                .statusCode(200)
                .body(not(containsString("<EventBridgeConfiguration")));
    }

    @Test
    void wrongNotificationConfigurationRootIsRejected() {
        String bucket = createBucket("wrong-root");

        putNotification(bucket, "<OtherConfiguration><EventBridgeConfiguration/></OtherConfiguration>")
                .statusCode(400)
                .body(containsString("<Code>MalformedXML</Code>"));
    }

    @Test
    void duplicateEventBridgeConfigurationsAreRejected() {
        String bucket = createBucket("duplicate");

        putNotification(bucket, """
                <NotificationConfiguration>
                    <EventBridgeConfiguration/>
                    <EventBridgeConfiguration/>
                </NotificationConfiguration>
                """)
                .statusCode(400)
                .body(containsString("<Code>MalformedXML</Code>"));
    }

    @Test
    void emptyNotificationConfigurationDisablesEventBridge() {
        String bucket = createBucket("empty");

        putNotification(bucket, "<NotificationConfiguration/>").statusCode(200);

        getNotification(bucket)
                .statusCode(200)
                .body(not(containsString("<EventBridgeConfiguration")));
    }

    private static String createBucket(String suffix) {
        String bucket = "eventbridge-validation-" + suffix;
        given().when().put("/" + bucket).then().statusCode(200);
        return bucket;
    }

    private static io.restassured.response.ValidatableResponse putNotification(String bucket, String body) {
        return given()
                .contentType("application/xml")
                .body(body)
                .when()
                .put("/" + bucket + "?notification")
                .then();
    }

    private static io.restassured.response.ValidatableResponse getNotification(String bucket) {
        return given()
                .when()
                .get("/" + bucket + "?notification")
                .then();
    }
}
