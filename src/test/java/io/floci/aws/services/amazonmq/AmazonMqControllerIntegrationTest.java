package io.floci.aws.services.amazonmq;

import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class AmazonMqControllerIntegrationTest {

    private static final AtomicLong ACCOUNTS = new AtomicLong(731300000000L);

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    private String createRabbitBroker(String name) {
        return createRabbitBroker(given(), name);
    }

    /** A broker created for {@code account} in {@code region}, deleted after the test unless the test did. */
    private String createRabbitBroker(String account, String region, String name) {
        String brokerId = createRabbitBroker(mq(account, region), name);
        cleanup.register(() -> mq(account, region).when().delete("/v1/brokers/{id}", brokerId));
        return brokerId;
    }

    private static RequestSpecification mq(String account, String region) {
        return given().header("Authorization", auth(account, region, "mq"));
    }

    private static String createRabbitBroker(RequestSpecification request, String name) {
        return postRabbitBroker(request, name)
            .statusCode(200)
            .extract().path("brokerId");
    }

    private static ValidatableResponse postRabbitBroker(RequestSpecification request, String name) {
        return request
            .contentType("application/json")
            .body("""
                {"brokerName": "%s", "engineType": "RABBITMQ",
                 "deploymentMode": "SINGLE_INSTANCE", "hostInstanceType": "mq.t3.micro",
                 "publiclyAccessible": false,
                 "users": [{"username": "admin", "password": "AdminPass123", "consoleAccess": true}]}
                """.formatted(name))
        .when()
            .post("/v1/brokers")
        .then();
    }

    @Test
    void createThenDescribeBroker() {
        String brokerId = createRabbitBroker("it-describe");

        given()
        .when()
            .get("/v1/brokers/{id}", brokerId)
        .then()
            .statusCode(200)
            .body("brokerName", equalTo("it-describe"))
            .body("engineType", equalTo("RABBITMQ"))
            .body("brokerState", equalTo("RUNNING"))
            .body("brokerInstances[0].endpoints[0]", startsWith("amqp://"))
            // AWS does not expose RabbitMQ users from DescribeBroker. Returning
            // them makes Terraform call the unsupported standalone User API.
            .body("users", nullValue())
            // internal bookkeeping is persisted but must never leak into the API
            .body("containerId", nullValue())
            .body("accountId", nullValue())
            .body("volumeId", nullValue());
    }

    @Test
    void describeBrokerReturnsCreateBrokerMembers() {
        String brokerId = given()
            .contentType("application/json")
            .body("""
                {"brokerName": "it-members", "engineType": "RABBITMQ",
                 "deploymentMode": "SINGLE_INSTANCE", "hostInstanceType": "mq.t3.micro",
                 "publiclyAccessible": false,
                 "securityGroups": ["sg-0123"], "subnetIds": ["subnet-0abc"],
                 "logs": {"general": true},
                 "maintenanceWindowStartTime": {"dayOfWeek": "MONDAY", "timeOfDay": "02:00", "timeZone": "UTC"},
                 "storageType": "EBS", "authenticationStrategy": "SIMPLE",
                 "encryptionOptions": {"kmsKeyId": "arn:aws:kms:us-east-1:000000000000:key/k1", "useAwsOwnedKey": false},
                 "configuration": {"id": "c-123", "revision": 2},
                 "users": [{"username": "admin", "password": "AdminPass123", "consoleAccess": true}]}
                """)
        .when()
            .post("/v1/brokers")
        .then()
            .statusCode(200)
            .extract().path("brokerId");

        given()
        .when()
            .get("/v1/brokers/{id}", brokerId)
        .then()
            .statusCode(200)
            .body("securityGroups", equalTo(List.of("sg-0123")))
            .body("subnetIds", equalTo(List.of("subnet-0abc")))
            .body("logs.general", equalTo(true))
            .body("logs.generalLogGroup", equalTo("/aws/amazonmq/broker/" + brokerId + "/general"))
            .body("maintenanceWindowStartTime.dayOfWeek", equalTo("MONDAY"))
            .body("maintenanceWindowStartTime.timeOfDay", equalTo("02:00"))
            .body("maintenanceWindowStartTime.timeZone", equalTo("UTC"))
            .body("storageType", equalTo("EBS"))
            .body("authenticationStrategy", equalTo("SIMPLE"))
            .body("encryptionOptions.kmsKeyId", equalTo("arn:aws:kms:us-east-1:000000000000:key/k1"))
            .body("encryptionOptions.useAwsOwnedKey", equalTo(false))
            .body("configurations.current.id", equalTo("c-123"))
            .body("configurations.current.revision", equalTo(2))
            .body("configuration", nullValue())
            // RabbitMQ users stay out of DescribeBroker (see createThenDescribeBroker).
            .body("users", nullValue());
    }

    @Test
    void describeBrokerOmitsUnsetOptionalMembers() {
        String brokerId = createRabbitBroker("it-minimal");

        given()
        .when()
            .get("/v1/brokers/{id}", brokerId)
        .then()
            .statusCode(200)
            .body("$", not(hasKey("securityGroups")))
            .body("logs.general", equalTo(false))
            .body("logs.generalLogGroup", equalTo("/aws/amazonmq/broker/" + brokerId + "/general"))
            .body("$", not(hasKey("configurations")))
            .body("$", not(hasKey("encryptionOptions")));
    }

    @Test
    void listBrokersIncludesCreated() {
        createRabbitBroker("it-list");

        given()
        .when()
            .get("/v1/brokers")
        .then()
            .statusCode(200)
            .body("brokerSummaries.brokerName", hasItem("it-list"));
    }

    @Test
    void userApiRejectedForRabbitMq() {
        // The standalone User API applies only to ActiveMQ; AWS rejects it for
        // RabbitMQ brokers. Users are managed through the RabbitMQ web console.
        String brokerId = createRabbitBroker("it-users");

        given()
            .contentType("application/json")
            .body("""
                {"password": "AnotherPass99", "consoleAccess": false}
                """)
        .when()
            .post("/v1/brokers/{id}/users/alice", brokerId)
        .then()
            .statusCode(400);

        given()
        .when()
            .get("/v1/brokers/{id}/users", brokerId)
        .then()
            .statusCode(400);
    }

    @Test
    void describeSharedResourcesReturnsEmptyList() {
        String brokerId = createRabbitBroker("it-shared");

        given()
        .when()
            .get("/v1/brokers/{id}/shared-resources", brokerId)
        .then()
            .statusCode(200)
            .body("sharedResources", equalTo(List.of()))
            .body("$", not(hasKey("nextToken")));
    }

    @Test
    void describeSharedResourcesUnknownBrokerIsNotFound() {
        given()
        .when()
            .get("/v1/brokers/{id}/shared-resources", "b-does-not-exist")
        .then()
            .statusCode(404)
            .body("__type", equalTo("NotFoundException"));
    }

    @Test
    void rejectsBrokerWithoutUser() {
        given()
            .contentType("application/json")
            .body("""
                {"brokerName": "it-nouser", "engineType": "RABBITMQ",
                 "deploymentMode": "SINGLE_INSTANCE", "hostInstanceType": "mq.t3.micro",
                 "publiclyAccessible": false}
                """)
        .when()
            .post("/v1/brokers")
        .then()
            .statusCode(400);
    }

    @Test
    void rejectsActiveMqEngine() {
        given()
            .contentType("application/json")
            .body("""
                {"brokerName": "it-activemq", "engineType": "ACTIVEMQ",
                 "deploymentMode": "SINGLE_INSTANCE", "hostInstanceType": "mq.t3.micro",
                 "publiclyAccessible": false}
                """)
        .when()
            .post("/v1/brokers")
        .then()
            .statusCode(400);
    }

    /**
     * Brokers are Regional: another Region of the account neither lists, describes, reboots nor deletes
     * one, nor describes its shared resources. Its name is unique in the account, so another Region refuses
     * it too. The test works in an account of its own, so each listing holds only its brokers.
     */
    @Test
    void aBrokerIsSeenOnlyInTheRegionItWasCreatedIn() {
        String account = Long.toString(ACCOUNTS.incrementAndGet());
        String inIreland = createRabbitBroker(account, "eu-west-1", "it-regional");

        mq(account, "us-east-1")
        .when()
            .get("/v1/brokers")
        .then()
            .statusCode(200)
            .body("brokerSummaries.brokerId", equalTo(List.of()));
        for (Function<RequestSpecification, Response> call : List.<Function<RequestSpecification, Response>>of(
                request -> request.get("/v1/brokers/{id}", inIreland),
                request -> request.get("/v1/brokers/{id}/shared-resources", inIreland),
                request -> request.contentType("application/json").post("/v1/brokers/{id}/reboot", inIreland),
                request -> request.delete("/v1/brokers/{id}", inIreland))) {
            call.apply(mq(account, "us-east-1"))
            .then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"));
        }

        postRabbitBroker(mq(account, "us-east-1"), "it-regional")
            .statusCode(409)
            .body("__type", equalTo("ConflictException"));
        String inVirginia = createRabbitBroker(account, "us-east-1", "it-regional-virginia");

        mq(account, "eu-west-1")
        .when()
            .get("/v1/brokers")
        .then()
            .statusCode(200)
            .body("brokerSummaries.brokerId", equalTo(List.of(inIreland)));
        mq(account, "us-east-1")
        .when()
            .get("/v1/brokers")
        .then()
            .statusCode(200)
            .body("brokerSummaries.brokerId", equalTo(List.of(inVirginia)));
    }
}
