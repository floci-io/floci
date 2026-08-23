package io.github.hectorvent.floci.services.iot;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IotDomainConfigurationIntegrationTest {

    private static final String CERTIFICATE_ARN =
            "arn:aws:acm:us-east-1:000000000000:certificate/6a3f2e10-0000-4000-8000-000000000001";

    @Test
    @Order(1)
    void describeMissingDomainConfigurationReturnsAwsError() {
        given()
        .when()
            .get("/domainConfigurations/missing-domain")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(2)
    void createCustomerManagedDomainConfiguration() {
        given()
            .contentType("application/json")
            .body("""
                {
                  "domainName": "iot.example.com",
                  "serverCertificateArns": ["%s"],
                  "validationCertificateArn": "%s",
                  "serviceType": "DATA",
                  "authorizerConfig": {"defaultAuthorizerName": "estate-auth", "allowAuthorizerOverride": true},
                  "tlsConfig": {"securityPolicy": "IotSecurityPolicy_TLS13_1_3_2022_10"},
                  "serverCertificateConfig": {"enableOCSPCheck": true},
                  "applicationProtocol": "SECURE_MQTT",
                  "authenticationType": "AWS_X509",
                  "tags": [{"Key": "tofu-estate", "Value": "probe1"}]
                }
                """.formatted(CERTIFICATE_ARN, CERTIFICATE_ARN))
        .when()
            .post("/domainConfigurations/estate-domain")
        .then()
            .statusCode(200)
            .body("domainConfigurationName", equalTo("estate-domain"))
            .body("domainConfigurationArn",
                    startsWith("arn:aws:iot:us-east-1:000000000000:domainconfiguration/estate-domain/"));
    }

    @Test
    @Order(3)
    void describeDomainConfigurationEchoesTheRequestAndIsEnabled() {
        given()
        .when()
            .get("/domainConfigurations/estate-domain")
        .then()
            .statusCode(200)
            .body("domainConfigurationName", equalTo("estate-domain"))
            .body("domainName", equalTo("iot.example.com"))
            .body("domainConfigurationStatus", equalTo("ENABLED"))
            .body("domainType", equalTo("CUSTOMER_MANAGED"))
            .body("serviceType", equalTo("DATA"))
            .body("serverCertificates[0].serverCertificateArn", equalTo(CERTIFICATE_ARN))
            .body("serverCertificates[0].serverCertificateStatus", equalTo("VALID"))
            .body("authorizerConfig.defaultAuthorizerName", equalTo("estate-auth"))
            .body("authorizerConfig.allowAuthorizerOverride", equalTo(true))
            .body("tlsConfig.securityPolicy", equalTo("IotSecurityPolicy_TLS13_1_3_2022_10"))
            .body("serverCertificateConfig.enableOCSPCheck", equalTo(true))
            .body("applicationProtocol", equalTo("SECURE_MQTT"))
            .body("authenticationType", equalTo("AWS_X509"))
            .body("lastStatusChangeDate", notNullValue());
    }

    @Test
    @Order(4)
    void createDomainConfigurationTagsAreReadableOnTheSharedTagPath() {
        String arn = given()
            .when()
                .get("/domainConfigurations/estate-domain")
            .then()
                .statusCode(200)
            .extract().path("domainConfigurationArn");

        given()
            .queryParam("resourceArn", arn)
        .when()
            .get("/tags")
        .then()
            .statusCode(200)
            .body("tags.Key", hasItem("tofu-estate"))
            .body("tags.Value", hasItem("probe1"));
    }

    @Test
    @Order(5)
    void listDomainConfigurationsReturnsTheConfiguration() {
        given()
        .when()
            .get("/domainConfigurations")
        .then()
            .statusCode(200)
            .body("domainConfigurations.domainConfigurationName", hasItem("estate-domain"));

        given()
            .queryParam("serviceType", "JOBS")
        .when()
            .get("/domainConfigurations")
        .then()
            .statusCode(200)
            .body("domainConfigurations.domainConfigurationName", not(hasItem("estate-domain")));
    }

    @Test
    @Order(6)
    void creatingTheSameDomainConfigurationTwiceIsRejected() {
        given()
            .contentType("application/json")
            .body("{}")
        .when()
            .post("/domainConfigurations/estate-domain")
        .then()
            .statusCode(409)
            .body("__type", equalTo("ResourceAlreadyExistsException"));
    }

    @Test
    @Order(7)
    void deletingAnEnabledDomainConfigurationIsRejected() {
        given()
            .contentType("application/json")
        .when()
            .delete("/domainConfigurations/estate-domain")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
    }

    @Test
    @Order(8)
    void updateDomainConfigurationDisablesItAndClearsTheAuthorizerConfig() {
        given()
            .contentType("application/json")
            .body("""
                {"domainConfigurationStatus": "DISABLED", "removeAuthorizerConfig": true}
                """)
        .when()
            .put("/domainConfigurations/estate-domain")
        .then()
            .statusCode(200)
            .body("domainConfigurationName", equalTo("estate-domain"));

        given()
        .when()
            .get("/domainConfigurations/estate-domain")
        .then()
            .statusCode(200)
            .body("domainConfigurationStatus", equalTo("DISABLED"))
            .body("authorizerConfig", equalTo(null));
    }

    @Test
    @Order(9)
    void deleteDomainConfigurationRemovesIt() {
        given()
            .contentType("application/json")
        .when()
            .delete("/domainConfigurations/estate-domain")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/domainConfigurations/estate-domain")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(10)
    void omittingTheDomainNameProducesAnAwsManagedEndpointDomain() {
        given()
            .contentType("application/json")
            .body("{}")
        .when()
            .post("/domainConfigurations/managed-domain")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/domainConfigurations/managed-domain")
        .then()
            .statusCode(200)
            .body("domainType", equalTo("AWS_MANAGED"))
            .body("domainName", notNullValue())
            .body("serverCertificates.size()", equalTo(0));

        given()
            .contentType("application/json")
            .body("{\"domainConfigurationStatus\": \"DISABLED\"}")
        .when()
            .put("/domainConfigurations/managed-domain")
        .then()
            .statusCode(200);

        given()
            .contentType("application/json")
        .when()
            .delete("/domainConfigurations/managed-domain")
        .then()
            .statusCode(200);
    }
}
