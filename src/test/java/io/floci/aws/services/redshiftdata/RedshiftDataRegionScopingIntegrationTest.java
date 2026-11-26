package io.floci.aws.services.redshiftdata;

import io.floci.aws.services.redshift.spectrum.SpectrumSession;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * A Redshift Data API statement is Regional: it is listed, described, read and cancelled only in the Region it was
 * run in. Another Region answers {@code ResourceNotFoundException}, as it does for an id that doesn't exist.
 */
@QuarkusTest
class RedshiftDataRegionScopingIntegrationTest {

    private static final String HOME = "us-east-1";
    private static final String OTHER = "eu-west-1";

    @Inject
    RedshiftDataService service;

    @InjectMock
    RedshiftDataResourceResolver resolver;

    @InjectMock
    RedshiftDataConnectionFactory connectionFactory;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void setUp() throws SQLException {
        when(resolver.resolve(any(), any())).thenReturn(new RedshiftDataResourceResolver.DatabaseTarget(
                "arn:aws:redshift:us-east-1:000000000000:cluster:regional", "127.0.0.1", 5439, "dev", "admin", "x",
                new SpectrumSession("000000000000", "000000000000:regional", "dev", List.of(), false)));
        when(connectionFactory.open(any())).thenThrow(new SQLException("connection refused"));
    }

    @AfterEach
    void clearStatements() {
        service.clear();
    }

    @Test
    void aStatementIsListedAndReachedOnlyInTheRegionItRanIn() {
        String id = call(HOME, "ExecuteStatement",
                "{\"ClusterIdentifier\":\"regional\",\"Database\":\"dev\",\"DbUser\":\"admin\",\"Sql\":\"select 1\"}")
                .statusCode(200).extract().path("Id");
        String byId = "{\"Id\":\"" + id + "\"}";
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                call(HOME, "DescribeStatement", byId).statusCode(200).body("Status", equalTo("FAILED")));

        call(OTHER, "ListStatements", "{}").statusCode(200).body("Statements.Id", not(hasItem(id)));
        notFound(call(OTHER, "DescribeStatement", byId));
        notFound(call(OTHER, "GetStatementResult", byId));
        notFound(call(OTHER, "GetStatementResultV2", byId));
        notFound(call(OTHER, "CancelStatement", byId));

        call(HOME, "ListStatements", "{}").statusCode(200).body("Statements.Id", hasItem(id));
        call(HOME, "DescribeStatement", byId).statusCode(200).body("Id", equalTo(id));
        call(HOME, "GetStatementResult", byId).statusCode(400).body("__type", equalTo("ValidationException"));
        call(HOME, "CancelStatement", byId).statusCode(400).body("__type", equalTo("ValidationException"));
    }

    private static ValidatableResponse call(String region, String action, String body) {
        return given()
                .contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "RedshiftData." + action)
                .header("Authorization",
                        "AWS4-HMAC-SHA256 Credential=AKID/20260101/" + region + "/redshift-data/aws4_request")
                .body(body)
                .when().post("/").then();
    }

    private static void notFound(ValidatableResponse response) {
        response.statusCode(400).body("__type", equalTo("ResourceNotFoundException"));
    }
}
