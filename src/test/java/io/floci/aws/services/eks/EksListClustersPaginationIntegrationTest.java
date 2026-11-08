package io.floci.aws.services.eks;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EksListClustersPaginationIntegrationTest {

    private static final String JSON = "application/json";
    private static final List<String> CLUSTERS = List.of("page-it-a", "page-it-b", "page-it-c");

    @Test
    @Order(1)
    void createClusters() {
        for (String name : CLUSTERS) {
            given().contentType(JSON)
                    .body("{\"name\":\"" + name + "\",\"roleArn\":\"arn:aws:iam::000000000000:role/eks-role\","
                            + "\"version\":\"1.29\"}")
                    .when().post("/clusters")
                    .then().statusCode(200);
        }
    }

    @Test
    @Order(9)
    void deleteClusters() {
        for (String name : CLUSTERS) {
            given().when().delete("/clusters/" + name).then().statusCode(200);
        }
    }

    @Test
    @Order(2)
    void listClustersPagesOneClusterAtATimeUntilTheLastPage() {
        List<String> seen = new ArrayList<>();
        String token = null;
        int pages = 0;
        do {
            JsonPath page = given()
                    .queryParam("maxResults", 1)
                    .queryParams(token == null ? Map.of() : Map.of("nextToken", token))
                    .when().get("/clusters")
                    .then().statusCode(200)
                    .extract().jsonPath();
            List<String> clusters = page.getList("clusters", String.class);
            assertThat(clusters, hasSize(lessThanOrEqualTo(1)));
            seen.addAll(clusters);
            token = page.getString("nextToken");
            pages++;
        } while (token != null && pages < 1000);

        assertThat(seen, hasItems(CLUSTERS.toArray(new String[0])));
        assertEquals(seen.size(), seen.stream().distinct().count());
        assertEquals(seen.size(), pages);
    }

    @Test
    @Order(2)
    void listClustersWithoutMaxResultsReturnsEveryClusterWithoutAToken() {
        given()
                .when().get("/clusters")
                .then().statusCode(200)
                .body("clusters", hasItems(CLUSTERS.toArray(new String[0])))
                .body("nextToken", nullValue());
    }

    @ParameterizedTest
    @Order(2)
    @ValueSource(ints = {0, 101})
    void listClustersRefusesAMaxResultsOutsideOneToOneHundred(int maxResults) {
        given()
                .queryParam("maxResults", maxResults)
                .when().get("/clusters")
                .then().statusCode(400)
                .body("__type", equalTo("InvalidParameterException"));
    }

    @Test
    @Order(2)
    void listClustersRefusesAnUnreadableToken() {
        given()
                .queryParam("nextToken", "not-a-token")
                .when().get("/clusters")
                .then().statusCode(400)
                .body("__type", equalTo("InvalidParameterException"))
                .body("message", not(nullValue()));
    }
}
