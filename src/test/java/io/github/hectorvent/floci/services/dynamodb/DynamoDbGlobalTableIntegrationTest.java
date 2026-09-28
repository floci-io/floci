package io.github.hectorvent.floci.services.dynamodb;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.parsing.Parser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/** CreateGlobalTable and its Describe, Update and List siblings, the 2017.11.29 API. */
@QuarkusTest
class DynamoDbGlobalTableIntegrationTest {

    @BeforeAll
    static void readTheJson10ResponsesAsJson() {
        RestAssured.registerParser("application/x-amz-json-1.0", Parser.JSON);
    }

    private static io.restassured.response.Response call(String target, String body) {
        return given()
            .header("X-Amz-Target", "DynamoDB_20120810." + target)
            .contentType("application/x-amz-json-1.0")
            .body(body.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        .when().post("/");
    }

    private static void createTable(String name, boolean streams, String viewType) {
        String stream = streams
                ? ",\"StreamSpecification\":{\"StreamEnabled\":true,\"StreamViewType\":\"" + viewType + "\"}"
                : "";
        call("CreateTable", "{\"TableName\":\"" + name + "\","
                + "\"AttributeDefinitions\":[{\"AttributeName\":\"pk\",\"AttributeType\":\"S\"}],"
                + "\"KeySchema\":[{\"AttributeName\":\"pk\",\"KeyType\":\"HASH\"}],"
                + "\"BillingMode\":\"PAY_PER_REQUEST\"" + stream + "}")
            .then().statusCode(200);
    }

    @Test
    void createDescribeAndUpdateAGlobalTable() {
        String name = "gt-lifecycle-" + Long.toString(System.nanoTime(), 36);
        createTable(name, true, "NEW_AND_OLD_IMAGES");

        call("CreateGlobalTable", "{\"GlobalTableName\":\"" + name + "\","
                + "\"ReplicationGroup\":[{\"RegionName\":\"eu-west-1\"}]}")
            .then().statusCode(200)
            .body("GlobalTableDescription.GlobalTableName", is(name))
            .body("GlobalTableDescription.GlobalTableStatus", is("ACTIVE"))
            .body("GlobalTableDescription.ReplicationGroup.RegionName",
                    containsInAnyOrder("eu-west-1", "us-east-1"))
            .body("GlobalTableDescription.ReplicationGroup.ReplicaStatus", hasItem("ACTIVE"));

        call("DescribeGlobalTable", "{\"GlobalTableName\":\"" + name + "\"}")
            .then().statusCode(200)
            .body("GlobalTableDescription.ReplicationGroup.RegionName", hasItem("eu-west-1"));

        call("UpdateGlobalTable", "{\"GlobalTableName\":\"" + name + "\","
                + "\"ReplicaUpdates\":[{\"Create\":{\"RegionName\":\"ap-south-1\"}},"
                + "{\"Delete\":{\"RegionName\":\"eu-west-1\"}}]}")
            .then().statusCode(200)
            .body("GlobalTableDescription.ReplicationGroup.RegionName", hasItem("ap-south-1"))
            .body("GlobalTableDescription.ReplicationGroup.RegionName", not(hasItem("eu-west-1")));

        call("ListGlobalTables", "{}")
            .then().statusCode(200)
            .body("GlobalTables.GlobalTableName", hasItem(name));
    }

    @Test
    void aSecondCreateOnTheSameTableIsRefused() {
        String name = "gt-dup-" + Long.toString(System.nanoTime(), 36);
        createTable(name, true, "NEW_AND_OLD_IMAGES");

        call("CreateGlobalTable", "{\"GlobalTableName\":\"" + name + "\","
                + "\"ReplicationGroup\":[{\"RegionName\":\"eu-west-1\"}]}").then().statusCode(200);

        call("CreateGlobalTable", "{\"GlobalTableName\":\"" + name + "\","
                + "\"ReplicationGroup\":[{\"RegionName\":\"eu-west-1\"}]}")
            .then().statusCode(400)
            .body("__type", is("GlobalTableAlreadyExistsException"));
    }

    @Test
    void aTableThatDoesNotExistIsTableNotFound() {
        call("CreateGlobalTable", "{\"GlobalTableName\":\"gt-missing-table\","
                + "\"ReplicationGroup\":[{\"RegionName\":\"eu-west-1\"}]}")
            .then().statusCode(400)
            .body("__type", is("TableNotFoundException"));
    }

    @Test
    void aTableWithoutBothStreamImagesCannotJoin() {
        String noStream = "gt-nostream-" + Long.toString(System.nanoTime(), 36);
        createTable(noStream, false, null);
        call("CreateGlobalTable", "{\"GlobalTableName\":\"" + noStream + "\","
                + "\"ReplicationGroup\":[{\"RegionName\":\"eu-west-1\"}]}")
            .then().statusCode(400)
            // The code matters: this file raises ValidationException everywhere else, so a
            // different shape here would slip past a client catching that one.
            .body("__type", is("ValidationException"));

        String newOnly = "gt-newonly-" + Long.toString(System.nanoTime(), 36);
        createTable(newOnly, true, "NEW_IMAGE");
        call("CreateGlobalTable", "{\"GlobalTableName\":\"" + newOnly + "\","
                + "\"ReplicationGroup\":[{\"RegionName\":\"eu-west-1\"}]}")
            .then().statusCode(400)
            .body("__type", is("ValidationException"));
    }

    @Test
    void describingATableThatIsNotGlobalIsNotFound() {
        String name = "gt-plain-" + Long.toString(System.nanoTime(), 36);
        createTable(name, true, "NEW_AND_OLD_IMAGES");
        call("DescribeGlobalTable", "{\"GlobalTableName\":\"" + name + "\"}")
            .then().statusCode(400)
            .body("__type", is("GlobalTableNotFoundException"));
    }

    @Test
    void updateRefusesADuplicateAddAndAnAbsentRemove() {
        String name = "gt-replicafault-" + Long.toString(System.nanoTime(), 36);
        createTable(name, true, "NEW_AND_OLD_IMAGES");
        call("CreateGlobalTable", "{\"GlobalTableName\":\"" + name + "\","
                + "\"ReplicationGroup\":[{\"RegionName\":\"eu-west-1\"}]}").then().statusCode(200);

        call("UpdateGlobalTable", "{\"GlobalTableName\":\"" + name + "\","
                + "\"ReplicaUpdates\":[{\"Create\":{\"RegionName\":\"eu-west-1\"}}]}")
            .then().statusCode(400)
            .body("__type", is("ReplicaAlreadyExistsException"));

        // The home region is a replica of its own global table, so re-adding it clashes too.
        call("UpdateGlobalTable", "{\"GlobalTableName\":\"" + name + "\","
                + "\"ReplicaUpdates\":[{\"Create\":{\"RegionName\":\"us-east-1\"}}]}")
            .then().statusCode(400)
            .body("__type", is("ReplicaAlreadyExistsException"));

        call("UpdateGlobalTable", "{\"GlobalTableName\":\"" + name + "\","
                + "\"ReplicaUpdates\":[{\"Delete\":{\"RegionName\":\"ap-northeast-1\"}}]}")
            .then().statusCode(400)
            .body("__type", is("ReplicaNotFoundException"));

        // UpdateTable keeps its idempotent behaviour: the same re-add is accepted there, because
        // the model declares neither fault for it.
        call("UpdateTable", "{\"TableName\":\"" + name + "\","
                + "\"ReplicaUpdates\":[{\"Create\":{\"RegionName\":\"eu-west-1\"}}]}")
            .then().statusCode(200);
    }

    @Test
    void listFiltersByRegion() {
        String name = "gt-filter-" + Long.toString(System.nanoTime(), 36);
        createTable(name, true, "NEW_AND_OLD_IMAGES");
        call("CreateGlobalTable", "{\"GlobalTableName\":\"" + name + "\","
                + "\"ReplicationGroup\":[{\"RegionName\":\"sa-east-1\"}]}").then().statusCode(200);

        call("ListGlobalTables", "{\"RegionName\":\"sa-east-1\"}")
            .then().statusCode(200)
            .body("GlobalTables.GlobalTableName", hasItem(name));

        call("ListGlobalTables", "{\"RegionName\":\"me-south-1\"}")
            .then().statusCode(200)
            .body("GlobalTables.GlobalTableName", not(hasItem(name)));
    }
}
