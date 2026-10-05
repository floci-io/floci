package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * The {@code Filter} of ListEmailIdentities, ListConfigurationSets and ListTenants on the wire: the
 * POST bindings the first two moved to when the member was added, beside the GET bindings older SDKs
 * still send, body parsing, the model's checks on the filter map, and what each list does with a key
 * it does not know. The match rules, the precedence and the token binding are covered by
 * {@code SesIdentityListFilterServiceTest}, {@code SesConfigurationSetListFilterServiceTest} and
 * {@code SesTenantListFilterServiceTest}.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SesListFilterV2IntegrationTest {

    private static final String REGION = "ca-west-1";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/" + REGION + "/ses/aws4_request";
    private static final String LIST_IDENTITIES = "/v2/email/list-identities";
    private static final String LIST_CONFIGURATION_SETS = "/v2/email/list-configuration-sets";
    private static final String LIST_TENANTS = "/v2/email/tenants/list";

    private boolean created;

    // Not @BeforeAll: the Quarkus test port is only bound once the first test starts.
    @BeforeEach
    void createResources() {
        if (created) {
            return;
        }
        for (String identity : new String[] {"alpha.filter.test", "beta.filter.test", "other.example.org"}) {
            send("/v2/email/identities", "{\"EmailIdentity\": \"" + identity + "\"}").statusCode(200);
        }
        for (String name : new String[] {"Filter-Alpha", "filter-beta", "unrelated"}) {
            send("/v2/email/configuration-sets", "{\"ConfigurationSetName\": \"" + name + "\"}").statusCode(200);
        }
        for (String name : new String[] {"Filter-Alpha", "filter-beta", "unrelated"}) {
            send("/v2/email/tenants", "{\"TenantName\": \"" + name + "\"}").statusCode(200);
        }
        created = true;
    }

    @Test
    void listIdentities_postWithoutBodyOrFilter_listsEverything() {
        send(LIST_IDENTITIES, "").statusCode(200)
                .body("EmailIdentities.IdentityName",
                        contains("alpha.filter.test", "beta.filter.test", "other.example.org"));
        send(LIST_IDENTITIES, "{\"Filter\": {}}").statusCode(200)
                .body("EmailIdentities.IdentityName",
                        contains("alpha.filter.test", "beta.filter.test", "other.example.org"));
    }

    @Test
    void listIdentities_postFiltersAndPages() {
        String token = send(LIST_IDENTITIES, "{\"Filter\": {\"IDENTITY_NAME_CONTAINS\": \"FILTER\", "
                + "\"IDENTITY_TYPE\": \"DOMAIN\"}, \"PageSize\": 1}").statusCode(200)
                .body("EmailIdentities.IdentityName", contains("alpha.filter.test"))
                .body("EmailIdentities[0].IdentityType", equalTo("DOMAIN"))
                .extract().path("NextToken");
        send(LIST_IDENTITIES, "{\"Filter\": {\"IDENTITY_NAME_CONTAINS\": \"filter\", "
                + "\"IDENTITY_TYPE\": \"DOMAIN\"}, \"PageSize\": 1, \"NextToken\": \"" + token + "\"}")
                .statusCode(200)
                .body("EmailIdentities.IdentityName", contains("beta.filter.test"))
                .body("NextToken", nullValue());
    }

    @Test
    void listIdentities_getStillServesOlderSdks() {
        given().header("Authorization", AUTH).queryParam("PageSize", 2).when().get("/v2/email/identities")
                .then().statusCode(200)
                .body("EmailIdentities.IdentityName", contains("alpha.filter.test", "beta.filter.test"));
    }

    @Test
    void listIdentities_refusesAKeyItDoesNotKnow_beforeTheValueAndThePageSize() {
        send(LIST_IDENTITIES, "{\"Filter\": {\"identity_name_contains\": \"\"}, \"PageSize\": 0}")
                .statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo("1 validation error detected: Value at 'filter' failed to satisfy "
                        + "constraint: Map keys must satisfy constraint: [Member must satisfy enum value set: "
                        + "[IDENTITY_NAME_CONTAINS, VERIFICATION_STATUS, IDENTITY_TYPE]]"));
        send(LIST_IDENTITIES, "{\"Filter\": {\"BOGUS\": null}}").statusCode(400)
                .body("message", equalTo("1 validation error detected: Value at 'filter' failed to satisfy "
                        + "constraint: Map keys must satisfy constraint: [Member must satisfy enum value set: "
                        + "[IDENTITY_NAME_CONTAINS, VERIFICATION_STATUS, IDENTITY_TYPE]]"));
        send(LIST_IDENTITIES, "{\"Filter\": {\"IDENTITY_NAME_CONTAINS\": \"\"}, \"PageSize\": 0}")
                .statusCode(400)
                .body("message", equalTo("1 validation error detected: Value at 'filter' failed to satisfy "
                        + "constraint: Map value must satisfy constraint: "
                        + "[Member must have length greater than or equal to 1]"));
    }

    @Test
    void listIdentities_aKnownKeyWithANullValue_isNoFilter() {
        send(LIST_IDENTITIES, "{\"Filter\": {\"IDENTITY_TYPE\": null}}").statusCode(200)
                .body("EmailIdentities.IdentityName",
                        contains("alpha.filter.test", "beta.filter.test", "other.example.org"));
    }

    @Test
    void listIdentities_wrongJsonTypes_areSerializationExceptions() {
        send(LIST_IDENTITIES, "{\"Filter\": {\"IDENTITY_NAME_CONTAINS\": 123}}").statusCode(400)
                .body("__type", equalTo("SerializationException"))
                .body("message", equalTo("NUMBER_VALUE can not be converted to a String"));
        send(LIST_IDENTITIES, "{\"Filter\": []}").statusCode(400)
                .body("__type", equalTo("SerializationException"))
                .body("message", equalTo("Start of list found where not expected"));
    }

    @Test
    void listConfigurationSets_postFiltersAndPages() {
        String token = send(LIST_CONFIGURATION_SETS,
                "{\"Filter\": {\"CONFIGURATION_SET_NAME_CONTAINS\": \"FILTER\"}, \"PageSize\": 1}")
                .statusCode(200)
                .body("ConfigurationSets", contains("Filter-Alpha"))
                .extract().path("NextToken");
        send(LIST_CONFIGURATION_SETS, "{\"Filter\": {\"CONFIGURATION_SET_NAME_CONTAINS\": \"filter\"}, "
                + "\"PageSize\": 1, \"NextToken\": \"" + token + "\"}")
                .statusCode(200)
                .body("ConfigurationSets", contains("filter-beta"))
                .body("NextToken", nullValue());
    }

    @Test
    void listConfigurationSets_ignoresAnUnknownKeyAndAnEmptyName() {
        send(LIST_CONFIGURATION_SETS, "{\"Filter\": {\"BOGUS\": \"x\", \"CONFIGURATION_SET_NAME_CONTAINS\": "
                + "\"alpha\"}}").statusCode(200)
                .body("ConfigurationSets", contains("Filter-Alpha"));
        send(LIST_CONFIGURATION_SETS, "{\"Filter\": {\"CONFIGURATION_SET_NAME_CONTAINS\": \"\"}}")
                .statusCode(200)
                .body("ConfigurationSets", contains("Filter-Alpha", "filter-beta", "unrelated"));
    }

    @Test
    void listConfigurationSets_getStillServesOlderSdks() {
        given().header("Authorization", AUTH).when().get("/v2/email/configuration-sets").then().statusCode(200)
                .body("ConfigurationSets", contains("Filter-Alpha", "filter-beta", "unrelated"));
    }

    @Test
    void listConfigurationSets_refusesANameOutOfBounds() {
        send(LIST_CONFIGURATION_SETS, "{\"Filter\": {\"CONFIGURATION_SET_NAME_CONTAINS\": \"ab\"}}")
                .statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo("CONFIGURATION_SET_NAME_CONTAINS must be between 3 and 64 characters"));
        send(LIST_CONFIGURATION_SETS, "{\"Filter\": {\"CONFIGURATION_SET_NAME_CONTAINS\": 1}}")
                .statusCode(400)
                .body("__type", equalTo("SerializationException"));
    }

    @Test
    void listTenants_filtersAndPages() {
        String token = send(LIST_TENANTS, "{\"Filter\": {\"TENANT_NAME_CONTAINS\": \"FILTER\", "
                + "\"SENDING_STATUS\": \"ENABLED\"}, \"PageSize\": 1}").statusCode(200)
                .body("Tenants.TenantName", contains("Filter-Alpha"))
                .extract().path("NextToken");
        send(LIST_TENANTS, "{\"Filter\": {\"TENANT_NAME_CONTAINS\": \"filter\", \"SENDING_STATUS\": "
                + "\"ENABLED\"}, \"PageSize\": 1, \"NextToken\": \"" + token + "\"}").statusCode(200)
                .body("Tenants.TenantName", contains("filter-beta"))
                .body("NextToken", nullValue());
        send(LIST_TENANTS, "{\"Filter\": {}}").statusCode(200)
                .body("Tenants.TenantName", contains("Filter-Alpha", "filter-beta", "unrelated"));
    }

    @Test
    void listTenants_refusesAKeyItDoesNotKnow_andAnEmptyValue() {
        send(LIST_TENANTS, "{\"Filter\": {\"BOGUS\": null}, \"PageSize\": 0}").statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo("1 validation error detected: Value at 'filter' failed to satisfy "
                        + "constraint: Map keys must satisfy constraint: [Member must satisfy enum value set: "
                        + "[SENDING_STATUS, TENANT_NAME_CONTAINS]]"));
        send(LIST_TENANTS, "{\"Filter\": {\"SENDING_STATUS\": \"\"}}").statusCode(400)
                .body("message", equalTo("1 validation error detected: Value at 'filter' failed to satisfy "
                        + "constraint: Map value must satisfy constraint: "
                        + "[Member must have length greater than or equal to 1]"));
        send(LIST_TENANTS, "{\"Filter\": {\"SENDING_STATUS\": \"enabled\"}}").statusCode(400)
                .body("message", equalTo("Invalid sending status <enabled>."));
    }

    private static ValidatableResponse send(String path, String body) {
        return given().contentType("application/json").header("Authorization", AUTH).body(body)
                .when().post(path).then();
    }
}
