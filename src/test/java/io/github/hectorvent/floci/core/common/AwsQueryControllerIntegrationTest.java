package io.github.hectorvent.floci.core.common;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class AwsQueryControllerIntegrationTest {

    @Test
    void missingActionParameterReturns400MissingAction() {
        given()
            .contentType("application/x-www-form-urlencoded")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .contentType("application/xml")
            .body("ErrorResponse.Error.Code", equalTo("MissingAction"))
            .body("ErrorResponse.Error.Message", equalTo("The request must contain the parameter Action"));
    }

    @Test
    void globalClusterActionFallbackWithoutAuthorizationHeader() {
        // Without a credential scope the service is inferred from the action. An action the RDS
        // handler serves but the inference list does not name is dispatched to SQS, which answers
        // UnsupportedOperation for it.
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeGlobalClusters")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body("DescribeGlobalClustersResponse.DescribeGlobalClustersResult.GlobalClusters",
                    equalTo(""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"DescribeDBClusterSnapshots", "DescribeDBSnapshots",
            "DescribeDBClusterParameterGroups", "DescribeDBClusterParameters", "DescribeOptionGroups",
            "CreateDBClusterParameterGroup", "ModifyDBClusterParameterGroup",
            "DeleteDBClusterParameterGroup", "CreateOptionGroup", "ModifyOptionGroup",
            "DeleteOptionGroup"})
    void everyRdsActionIsInferredWithoutAnAuthorizationHeader(String action) {
        // Whatever each answers — a result or a validation error for the parameters left out here —
        // RDS has to be the one answering. An action the handler serves but the inference list does
        // not name is dispatched to SQS instead.
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", action)
        .when()
            .post("/")
        .then()
            .body(not(containsString("sqs.amazonaws.com")))
            .body(not(containsString("is not supported by SQS")));
    }

    @Test
    void ec2ActionFallbackWithoutAuthorizationHeader() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeRegions")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body("DescribeRegionsResponse.regionInfo.item.size()", greaterThan(0));
    }
}
