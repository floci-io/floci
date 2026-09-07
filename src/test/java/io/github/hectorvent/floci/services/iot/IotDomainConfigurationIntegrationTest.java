package io.github.hectorvent.floci.services.iot;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * Drives the domain configuration routes the way the AWS SDKs do (REST-JSON under
 * {@code /domainConfigurations}) and checks the wire shapes and error codes AWS IoT returns.
 */
@QuarkusTest
class IotDomainConfigurationIntegrationTest {

    private static final String NAME = "it-domain";
    private static final String CERTIFICATE_ARN =
            "arn:aws:acm:us-east-1:000000000000:certificate/11111111-1111-1111-1111-111111111111";

    private static String createBody(String domainName, String serviceType) {
        return """
            {
              "domainName": "%s",
              "serverCertificateArns": ["%s"],
              "serviceType": "%s"
            }
            """.formatted(domainName, CERTIFICATE_ARN, serviceType);
    }

    @Test
    void domainConfigurationLifecycleMatchesAws() {
        String arn = given()
            .contentType("application/json")
            .body("""
                {
                  "domainName": "iot.it.example.com",
                  "serverCertificateArns": ["%s"],
                  "authorizerConfig": {"defaultAuthorizerName": "it-authorizer", "allowAuthorizerOverride": true},
                  "tags": [{"Key": "env", "Value": "it"}]
                }
                """.formatted(CERTIFICATE_ARN))
        .when()
            .post("/domainConfigurations/" + NAME)
        .then()
            .statusCode(200)
            .body("domainConfigurationName", equalTo(NAME))
            .body("domainConfigurationArn",
                    startsWith("arn:aws:iot:us-east-1:000000000000:domainconfiguration/" + NAME + "/"))
            .extract().path("domainConfigurationArn");

        given()
            .contentType("application/json")
            .body(createBody("iot.it.example.com", "DATA"))
        .when()
            .post("/domainConfigurations/" + NAME)
        .then()
            .statusCode(409)
            .body("__type", equalTo("ResourceAlreadyExistsException"));

        given()
        .when()
            .get("/domainConfigurations/" + NAME)
        .then()
            .statusCode(200)
            .body("domainConfigurationName", equalTo(NAME))
            .body("domainConfigurationArn", equalTo(arn))
            .body("domainName", equalTo("iot.it.example.com"))
            .body("domainConfigurationStatus", equalTo("ENABLED"))
            .body("serviceType", equalTo("DATA"))
            .body("domainType", equalTo("CUSTOMER_MANAGED"))
            .body("serverCertificates[0].serverCertificateArn", equalTo(CERTIFICATE_ARN))
            .body("serverCertificates[0].serverCertificateStatus", equalTo("VALID"))
            .body("authorizerConfig.defaultAuthorizerName", equalTo("it-authorizer"))
            .body("authorizerConfig.allowAuthorizerOverride", equalTo(true))
            .body("tlsConfig.securityPolicy", equalTo("IoTSecurityPolicy_TLS13_1_2_2022_10"))
            .body("serverCertificateConfig.enableOCSPCheck", equalTo(false))
            .body("lastStatusChangeDate", notNullValue());

        given()
            .queryParam("resourceArn", arn)
        .when()
            .get("/tags")
        .then()
            .statusCode(200)
            .body("tags.Key", hasItem("env"))
            .body("tags.Value", hasItem("it"));

        given()
            .contentType("application/json")
            .body("{\"resourceArn\": \"" + arn + "\", \"tags\": [{\"Key\": \"owner\", \"Value\": \"iot\"}]}")
        .when()
            .post("/tags")
        .then()
            .statusCode(200);

        given()
            .contentType("application/json")
            .body("{\"resourceArn\": \"" + arn + "\", \"tagKeys\": [\"env\"]}")
        .when()
            .post("/untag")
        .then()
            .statusCode(200);

        given()
            .queryParam("resourceArn", arn)
        .when()
            .get("/tags")
        .then()
            .statusCode(200)
            .body("tags.Key", hasItem("owner"))
            .body("tags.Key", not(hasItem("env")));

        given()
            .contentType("application/json")
            .body(createBody("jobs.it.example.com", "JOBS"))
        .when()
            .post("/domainConfigurations/" + NAME + "-jobs")
        .then()
            .statusCode(200);

        given()
            .queryParam("serviceType", "JOBS")
        .when()
            .get("/domainConfigurations")
        .then()
            .statusCode(200)
            .body("domainConfigurations.domainConfigurationName", hasItem(NAME + "-jobs"))
            .body("domainConfigurations.domainConfigurationName", not(hasItem(NAME)))
            .body("domainConfigurations[0].serviceType", equalTo("JOBS"))
            .body("domainConfigurations[0].domainConfigurationArn", notNullValue());

        String marker = given()
            .queryParam("pageSize", 1)
        .when()
            .get("/domainConfigurations")
        .then()
            .statusCode(200)
            .body("domainConfigurations.size()", equalTo(1))
            .body("nextMarker", notNullValue())
            .extract().path("nextMarker");

        given()
            .queryParam("pageSize", 1)
            .queryParam("marker", marker)
        .when()
            .get("/domainConfigurations")
        .then()
            .statusCode(200)
            .body("domainConfigurations.size()", equalTo(1));

        given()
        .when()
            .delete("/domainConfigurations/" + NAME)
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));

        given()
            .contentType("application/json")
            .body("{\"domainConfigurationStatus\": \"DISABLED\", \"removeAuthorizerConfig\": true}")
        .when()
            .put("/domainConfigurations/" + NAME)
        .then()
            .statusCode(200)
            .body("domainConfigurationName", equalTo(NAME))
            .body("domainConfigurationArn", equalTo(arn));

        given()
        .when()
            .get("/domainConfigurations/" + NAME)
        .then()
            .statusCode(200)
            .body("domainConfigurationStatus", equalTo("DISABLED"))
            .body("authorizerConfig", equalTo(null));

        given()
        .when()
            .delete("/domainConfigurations/" + NAME)
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/domainConfigurations/" + NAME)
        .then()
            .statusCode(404)
            .header("x-amzn-ErrorType", equalTo("ResourceNotFoundException"))
            .body("__type", equalTo("ResourceNotFoundException"));

        given()
            .contentType("application/json")
            .body("{\"domainConfigurationStatus\": \"DISABLED\"}")
        .when()
            .put("/domainConfigurations/" + NAME + "-jobs")
        .then()
            .statusCode(200);
        given()
        .when()
            .delete("/domainConfigurations/" + NAME + "-jobs")
        .then()
            .statusCode(200);
    }

    @Test
    void awsManagedConfigurationsExistWithoutBeingCreated() {
        given()
        .when()
            .get("/domainConfigurations/iot:Data-ATS")
        .then()
            .statusCode(200)
            .body("domainConfigurationName", equalTo("iot:Data-ATS"))
            .body("domainConfigurationArn",
                    startsWith("arn:aws:iot:us-east-1:000000000000:domainconfiguration/iot:Data-ATS/"))
            .body("domainName", equalTo("localhost:4566"))
            .body("domainType", equalTo("AWS_MANAGED"))
            .body("serviceType", equalTo("DATA"))
            .body("domainConfigurationStatus", equalTo("ENABLED"))
            .body("serverCertificates.size()", equalTo(0))
            .body("tlsConfig.securityPolicy", equalTo("IoTSecurityPolicy_TLS13_1_2_2022_10"));

        given()
        .when()
            .get("/domainConfigurations")
        .then()
            .statusCode(200)
            .body("domainConfigurations.domainConfigurationName",
                    hasItems("iot:Data-ATS", "iot:Data", "iot:CredentialProvider", "iot:Jobs"));

        given()
            .queryParam("serviceType", "CREDENTIAL_PROVIDER")
        .when()
            .get("/domainConfigurations")
        .then()
            .statusCode(200)
            .body("domainConfigurations.domainConfigurationName", hasItem("iot:CredentialProvider"))
            .body("domainConfigurations.domainConfigurationName", not(hasItem("iot:Jobs")));

        given()
            .contentType("application/json")
            .body("{\"domainConfigurationStatus\": \"DISABLED\"}")
        .when()
            .put("/domainConfigurations/iot:Data")
        .then()
            .statusCode(200)
            .body("domainConfigurationName", equalTo("iot:Data"));

        given()
        .when()
            .get("/domainConfigurations/iot:Data")
        .then()
            .statusCode(200)
            .body("domainConfigurationStatus", equalTo("DISABLED"));

        given()
        .when()
            .delete("/domainConfigurations/iot:Data")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));

        given()
            .contentType("application/json")
            .body("{\"domainConfigurationStatus\": \"ENABLED\"}")
        .when()
            .put("/domainConfigurations/iot:Data")
        .then()
            .statusCode(200);
    }

    @Test
    void unknownDomainConfigurationIsNotFoundOnEveryRoute() {
        given()
        .when()
            .get("/domainConfigurations/absent")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));

        given()
            .contentType("application/json")
            .body("{\"domainConfigurationStatus\": \"ENABLED\"}")
        .when()
            .put("/domainConfigurations/absent")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));

        given()
        .when()
            .delete("/domainConfigurations/absent")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createRejectsAReservedNameAndListRejectsABadPageSize() {
        given()
            .contentType("application/json")
            .body(createBody("data.it.example.com", "DATA"))
        .when()
            .post("/domainConfigurations/iot:Data-ATS")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));

        given()
            .queryParam("pageSize", 0)
        .when()
            .get("/domainConfigurations")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
    }

    @Test
    void describeEndpointAcceptsTheCredentialProviderEndpointType() {
        given()
            .queryParam("endpointType", "iot:CredentialProvider")
        .when()
            .get("/endpoint")
        .then()
            .statusCode(200)
            .body("endpointAddress", equalTo("localhost:4566"));
    }
}
