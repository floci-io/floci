package io.github.hectorvent.floci.services.eventbridge;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EventBridgeConnectionIntegrationTest {

    private static final String EB_CT = "application/x-amz-json-1.1";

    private static String connectionArn;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void createConnection() {
        connectionArn = given()
                .contentType(EB_CT)
                .header("X-Amz-Target", "AWSEvents.CreateConnection")
                .body("""
                        {
                          "Name": "test-http-ingress-connection",
                          "Description": "Connection integration test",
                          "AuthorizationType": "API_KEY",
                          "AuthParameters": {
                            "ApiKeyAuthParameters": {
                              "ApiKeyName": "x-api-key",
                              "ApiKeyValue": "super-secret-value"
                            },
                            "InvocationHttpParameters": {
                              "HeaderParameters": [
                                {"Key": "x-tenant", "Value": "acme", "IsValueSecret": false},
                                {"Key": "x-token", "Value": "hidden-token", "IsValueSecret": true}
                              ]
                            }
                          }
                        }
                        """)
                .when().post("/")
                .then().statusCode(200)
                .body("ConnectionArn", startsWith("arn:aws:events:us-east-1:000000000000:connection/test-http-ingress-connection/"))
                .body("ConnectionState", equalTo("AUTHORIZED"))
                .body("CreationTime", notNullValue())
                .body("LastModifiedTime", notNullValue())
                .extract().jsonPath().getString("ConnectionArn");
    }

    @Test
    @Order(2)
    void createDuplicateConnectionFails() {
        given()
                .contentType(EB_CT)
                .header("X-Amz-Target", "AWSEvents.CreateConnection")
                .body("""
                        {
                          "Name": "test-http-ingress-connection",
                          "AuthorizationType": "API_KEY",
                          "AuthParameters": {
                            "ApiKeyAuthParameters": {"ApiKeyName": "k", "ApiKeyValue": "v"}
                          }
                        }
                        """)
                .when().post("/")
                .then().statusCode(400)
                .body("__type", equalTo("ResourceAlreadyExistsException"));
    }

    @Test
    @Order(3)
    void describeConnection() {
        given()
                .contentType(EB_CT)
                .header("X-Amz-Target", "AWSEvents.DescribeConnection")
                .body("{\"Name\":\"test-http-ingress-connection\"}")
                .when().post("/")
                .then().statusCode(200)
                .body("Name", equalTo("test-http-ingress-connection"))
                .body("ConnectionArn", equalTo(connectionArn))
                .body("ConnectionState", equalTo("AUTHORIZED"))
                .body("AuthorizationType", equalTo("API_KEY"))
                .body("Description", equalTo("Connection integration test"))
                .body("SecretArn", startsWith("arn:aws:secretsmanager:us-east-1:000000000000:secret:events!connection/"))
                .body("CreationTime", notNullValue())
                .body("LastAuthorizedTime", notNullValue());
    }

    @Test
    @Order(5)
    void updateConnection() {
        given()
                .contentType(EB_CT)
                .header("X-Amz-Target", "AWSEvents.UpdateConnection")
                .body("""
                        {
                          "Name": "test-http-ingress-connection",
                          "Description": "Updated description",
                          "AuthorizationType": "BASIC",
                          "AuthParameters": {
                            "BasicAuthParameters": {"Username": "user", "Password": "pass"}
                          }
                        }
                        """)
                .when().post("/")
                .then().statusCode(200)
                .body("ConnectionArn", equalTo(connectionArn))
                .body("ConnectionState", equalTo("AUTHORIZED"));

        given()
                .contentType(EB_CT)
                .header("X-Amz-Target", "AWSEvents.DescribeConnection")
                .body("{\"Name\":\"test-http-ingress-connection\"}")
                .when().post("/")
                .then().statusCode(200)
                .body("Description", equalTo("Updated description"))
                .body("AuthorizationType", equalTo("BASIC"))
                .body("AuthParameters.BasicAuthParameters.Username", equalTo("user"));
    }

    @Test
    @Order(6)
    void listConnections() {
        given()
                .contentType(EB_CT)
                .header("X-Amz-Target", "AWSEvents.ListConnections")
                .body("{\"NamePrefix\":\"test-http-ingress\"}")
                .when().post("/")
                .then().statusCode(200)
                .body("Connections", hasSize(1))
                .body("Connections[0].Name", equalTo("test-http-ingress-connection"))
                .body("Connections[0].ConnectionArn", equalTo(connectionArn))
                .body("Connections[0].ConnectionState", equalTo("AUTHORIZED"))
                .body("Connections[0].AuthParameters", nullValue());
    }

    @Test
    @Order(9)
    void deleteConnection() {
        given()
                .contentType(EB_CT)
                .header("X-Amz-Target", "AWSEvents.DeleteConnection")
                .body("{\"Name\":\"test-http-ingress-connection\"}")
                .when().post("/")
                .then().statusCode(200)
                .body("ConnectionArn", equalTo(connectionArn))
                .body("ConnectionState", equalTo("DELETING"));

        given()
                .contentType(EB_CT)
                .header("X-Amz-Target", "AWSEvents.DescribeConnection")
                .body("{\"Name\":\"test-http-ingress-connection\"}")
                .when().post("/")
                .then().statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }
}
