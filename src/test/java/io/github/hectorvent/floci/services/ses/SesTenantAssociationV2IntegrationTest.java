package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * Integration tests for the SES v2 tenant resource associations (Phase 2): the RPC-style
 * {@code /tenants/resources}, {@code /tenants/resources/delete}, {@code /tenants/resources/list} and
 * {@code /resources/tenants/list}. The wire quirks are probe-confirmed against real AWS: ResourceType
 * values are the ARN segments (identity / configuration-set / template) — not the SDK enum spelling —
 * delete is idempotent, backing-resource deletion is blocked while associated, and DeleteTenant
 * cascades.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesTenantAssociationV2IntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ses/aws4_request";
    private static final String TENANT = "floci-assoc-tenant";
    private static final String TENANT_B = "floci-assoc-tenant-b";
    private static final String IDENTITY = "floci-assoc.example.com";
    private static final String CONFIG_SET = "floci-assoc-cs";
    private static final String TEMPLATE = "floci-assoc-tpl";
    private static final String ARN_PREFIX = "arn:aws:ses:us-east-1:000000000000:";
    private static final String IDENTITY_ARN = ARN_PREFIX + "identity/" + IDENTITY;
    private static final String CONFIG_SET_ARN = ARN_PREFIX + "configuration-set/" + CONFIG_SET;
    private static final String TEMPLATE_ARN = ARN_PREFIX + "template/" + TEMPLATE;

    private static io.restassured.specification.RequestSpecification v2() {
        return given().contentType("application/json").header("Authorization", AUTH);
    }

    private static void associate(String tenant, String arn) {
        v2().body("{\"TenantName\":\"" + tenant + "\",\"ResourceArn\":\"" + arn + "\"}")
                .when().post("/v2/email/tenants/resources").then().statusCode(200);
    }

    @Test
    @Order(1)
    void setup_createTenantsAndResources() {
        v2().body("{\"TenantName\":\"" + TENANT + "\"}")
                .when().post("/v2/email/tenants").then().statusCode(200);
        v2().body("{\"TenantName\":\"" + TENANT_B + "\"}")
                .when().post("/v2/email/tenants").then().statusCode(200);
        v2().body("{\"EmailIdentity\":\"" + IDENTITY + "\"}")
                .when().post("/v2/email/identities").then().statusCode(200);
        v2().body("{\"ConfigurationSetName\":\"" + CONFIG_SET + "\"}")
                .when().post("/v2/email/configuration-sets").then().statusCode(200);
        v2().body("{\"TemplateName\":\"" + TEMPLATE + "\",\"TemplateContent\":{\"Subject\":\"s\",\"Text\":\"t\"}}")
                .when().post("/v2/email/templates").then().statusCode(200);
    }

    @Test
    @Order(2)
    void createAssociation_returnsEmptyObject() {
        v2().body("{\"TenantName\":\"" + TENANT + "\",\"ResourceArn\":\"" + IDENTITY_ARN + "\"}")
                .when().post("/v2/email/tenants/resources").then().statusCode(200)
                .body("isEmpty()", equalTo(true));
        associate(TENANT, CONFIG_SET_ARN);
        associate(TENANT, TEMPLATE_ARN);
        associate(TENANT_B, IDENTITY_ARN);
    }

    @Test
    @Order(3)
    void createAssociation_duplicate_returnsAlreadyExists() {
        v2().body("{\"TenantName\":\"" + TENANT + "\",\"ResourceArn\":\"" + IDENTITY_ARN + "\"}")
                .when().post("/v2/email/tenants/resources").then().statusCode(400)
                .body("__type", equalTo("AlreadyExistsException"))
                // "Resources" is AWS's own grammar.
                .body("message", equalTo("Resources " + IDENTITY_ARN
                        + " has already been associated with tenant " + TENANT));
    }

    @Test
    @Order(4)
    void createAssociation_errorMatrix() {
        v2().body("{\"TenantName\":\"ghost-tenant\",\"ResourceArn\":\"" + IDENTITY_ARN + "\"}")
                .when().post("/v2/email/tenants/resources").then().statusCode(404)
                .body("__type", equalTo("NotFoundException"))
                .body("message", equalTo("The requested tenant <ghost-tenant> does not exist."));

        // Missing resources 404 with a per-type message; the configuration-set trailing colon is AWS's.
        v2().body("{\"TenantName\":\"" + TENANT + "\",\"ResourceArn\":\"" + ARN_PREFIX
                        + "configuration-set/ghost-cs\"}")
                .when().post("/v2/email/tenants/resources").then().statusCode(404)
                .body("message", equalTo("Configuration set <ghost-cs> does not exist:"));
        v2().body("{\"TenantName\":\"" + TENANT + "\",\"ResourceArn\":\"" + ARN_PREFIX
                        + "identity/ghost.example.com\"}")
                .when().post("/v2/email/tenants/resources").then().statusCode(404)
                .body("message", equalTo("Identity <ghost.example.com> does not exist"));
        v2().body("{\"TenantName\":\"" + TENANT + "\",\"ResourceArn\":\"" + ARN_PREFIX
                        + "template/ghost-tpl\"}")
                .when().post("/v2/email/tenants/resources").then().statusCode(404)
                .body("message", equalTo("Email template <ghost-tpl> does not exist"));

        v2().body("{\"TenantName\":\"" + TENANT + "\",\"ResourceArn\":\"not-an-arn\"}")
                .when().post("/v2/email/tenants/resources").then().statusCode(400)
                .body("message", equalTo("Provided resource identifier is not an SES resource"));
        v2().body("{\"TenantName\":\"" + TENANT
                        + "\",\"ResourceArn\":\"arn:aws:sqs:us-east-1:000000000000:q\"}")
                .when().post("/v2/email/tenants/resources").then().statusCode(400)
                .body("message", equalTo("Provided ARN is not in SES resource ARN format"));
        v2().body("{\"TenantName\":\"" + TENANT
                        + "\",\"ResourceArn\":\"arn:aws:ses:us-east-1:000000000000:contact-list/x\"}")
                .when().post("/v2/email/tenants/resources").then().statusCode(400)
                .body("message", equalTo("Unsupported resource type: contact-list"));
        v2().body("{\"TenantName\":\"" + TENANT
                        + "\",\"ResourceArn\":\"arn:aws:ses:eu-west-1:000000000000:identity/" + IDENTITY + "\"}")
                .when().post("/v2/email/tenants/resources").then().statusCode(400)
                .body("message", equalTo("Resource <arn:aws:ses:eu-west-1:000000000000:identity/"
                        + IDENTITY + "> must be in the same region"));
        v2().body("{\"TenantName\":\"" + TENANT
                        + "\",\"ResourceArn\":\"arn:aws:ses:us-east-1:111111111111:identity/" + IDENTITY + "\"}")
                .when().post("/v2/email/tenants/resources").then().statusCode(400)
                .body("message", equalTo("Resource <arn:aws:ses:us-east-1:111111111111:identity/"
                        + IDENTITY + "> must be in the same account"));

        v2().body("{\"TenantName\":\"\",\"ResourceArn\":\"" + IDENTITY_ARN + "\"}")
                .when().post("/v2/email/tenants/resources").then().statusCode(400)
                .body("message", equalTo("TenantName cannot be empty"));
        // An absent TenantName gets the same message (probe-confirmed) — no Smithy not-null here.
        v2().body("{\"ResourceArn\":\"" + IDENTITY_ARN + "\"}")
                .when().post("/v2/email/tenants/resources").then().statusCode(400)
                .body("message", equalTo("TenantName cannot be empty"));
        v2().body("{\"TenantName\":\"" + TENANT + "\"}")
                .when().post("/v2/email/tenants/resources").then().statusCode(400)
                .body("message", equalTo("1 validation error detected: Value at 'resourceArn' failed "
                        + "to satisfy constraint: Member must not be null"));
    }

    @Test
    @Order(5)
    void listTenantResources_sortedByArn_lowercaseTypes_nullNextToken() {
        v2().body("{\"TenantName\":\"" + TENANT + "\"}")
                .when().post("/v2/email/tenants/resources/list").then().statusCode(200)
                .body("NextToken", nullValue())
                .body("TenantResources", hasSize(3))
                .body("TenantResources[0].ResourceType", equalTo("configuration-set"))
                .body("TenantResources[0].ResourceArn", equalTo(CONFIG_SET_ARN))
                .body("TenantResources[1].ResourceType", equalTo("identity"))
                .body("TenantResources[2].ResourceType", equalTo("template"));
    }

    @Test
    @Order(6)
    void listTenantResources_filterUsesWireValues() {
        v2().body("{\"TenantName\":\"" + TENANT + "\",\"Filter\":{\"RESOURCE_TYPE\":\"identity\"}}")
                .when().post("/v2/email/tenants/resources/list").then().statusCode(200)
                .body("TenantResources", hasSize(1))
                .body("TenantResources[0].ResourceArn", equalTo(IDENTITY_ARN));

        // Real AWS rejects the SDK's enum spelling — only the ARN-segment values are valid.
        v2().body("{\"TenantName\":\"" + TENANT + "\",\"Filter\":{\"RESOURCE_TYPE\":\"EMAIL_IDENTITY\"}}")
                .when().post("/v2/email/tenants/resources/list").then().statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo("Invalid resource type EMAIL_IDENTITY specified."));
    }

    @Test
    @Order(7)
    void listTenantResources_pagingValidation() {
        v2().body("{\"TenantName\":\"" + TENANT + "\",\"PageSize\":0}")
                .when().post("/v2/email/tenants/resources/list").then().statusCode(400)
                .body("message", equalTo("1 validation error detected: Value '0' at 'pageSize' failed "
                        + "to satisfy constraint: Member must have value greater than or equal to 1"));
        v2().body("{\"TenantName\":\"" + TENANT + "\",\"NextToken\":\"garbage-token\"}")
                .when().post("/v2/email/tenants/resources/list").then().statusCode(400)
                .body("message", equalTo("Invalid Next Token"));
        // An integral value outside the int range must be rejected, not silently truncated
        // (4294967296 would truncate to 0 and produce the wrong Smithy message).
        v2().body("{\"TenantName\":\"" + TENANT + "\",\"PageSize\":4294967296}")
                .when().post("/v2/email/tenants/resources/list").then().statusCode(400)
                .body("__type", equalTo("SerializationException"));
        v2().body("{\"TenantName\":\"ghost-tenant\"}")
                .when().post("/v2/email/tenants/resources/list").then().statusCode(404)
                .body("__type", equalTo("NotFoundException"));
    }

    @Test
    @Order(8)
    void listResourceTenants_returnsMetadataWithoutTenantArn() {
        v2().body("{\"ResourceArn\":\"" + IDENTITY_ARN + "\"}")
                .when().post("/v2/email/resources/tenants/list").then().statusCode(200)
                .body("NextToken", nullValue())
                .body("ResourceTenants", hasSize(2))
                .body("ResourceTenants[0].TenantName", equalTo(TENANT))
                .body("ResourceTenants[0].TenantId", startsWith("tn-"))
                .body("ResourceTenants[0].ResourceArn", equalTo(IDENTITY_ARN))
                .body("ResourceTenants[0].AssociatedTimestamp", notNullValue())
                .body("ResourceTenants[0].TenantArn", nullValue())
                .body("ResourceTenants[1].TenantName", equalTo(TENANT_B));

        v2().body("{\"ResourceArn\":\"" + ARN_PREFIX + "configuration-set/ghost-cs\"}")
                .when().post("/v2/email/resources/tenants/list").then().statusCode(404)
                .body("message", equalTo("Configuration set <ghost-cs> does not exist:"));
        v2().body("{\"ResourceArn\":\"nope\"}")
                .when().post("/v2/email/resources/tenants/list").then().statusCode(400)
                .body("message", equalTo("Provided resource identifier is not an SES resource"));
    }

    @Test
    @Order(9)
    void deleteBackingResource_whileAssociated_isBlocked() {
        v2().when().delete("/v2/email/identities/" + IDENTITY).then().statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo("Cannot delete <" + IDENTITY_ARN + "> because it has tenant "
                        + "associations. Remove all tenant associations and try again."));
        v2().when().delete("/v2/email/configuration-sets/" + CONFIG_SET).then().statusCode(400)
                .body("message", equalTo("Cannot delete <" + CONFIG_SET_ARN + "> because it has tenant "
                        + "associations. Remove all tenant associations and try again."));
        v2().when().delete("/v2/email/templates/" + TEMPLATE).then().statusCode(400)
                .body("message", equalTo("Cannot delete <" + TEMPLATE_ARN + "> because it has tenant "
                        + "associations. Remove all tenant associations and try again."));
    }

    @Test
    @Order(10)
    void deleteAssociation_isIdempotent_butValidatesTenantAndResource() {
        v2().body("{\"TenantName\":\"" + TENANT + "\",\"ResourceArn\":\"" + TEMPLATE_ARN + "\"}")
                .when().post("/v2/email/tenants/resources/delete").then().statusCode(200)
                .body("isEmpty()", equalTo(true));
        // Removing an association that no longer exists is a silent success on AWS.
        v2().body("{\"TenantName\":\"" + TENANT + "\",\"ResourceArn\":\"" + TEMPLATE_ARN + "\"}")
                .when().post("/v2/email/tenants/resources/delete").then().statusCode(200);

        v2().body("{\"TenantName\":\"ghost-tenant\",\"ResourceArn\":\"" + IDENTITY_ARN + "\"}")
                .when().post("/v2/email/tenants/resources/delete").then().statusCode(404)
                .body("__type", equalTo("NotFoundException"));
        v2().body("{\"TenantName\":\"" + TENANT + "\",\"ResourceArn\":\"" + ARN_PREFIX
                        + "configuration-set/ghost-cs\"}")
                .when().post("/v2/email/tenants/resources/delete").then().statusCode(404)
                .body("message", equalTo("Configuration set <ghost-cs> does not exist:"));
    }

    @Test
    @Order(11)
    void deleteTenant_cascadesAssociations_andRecreationStartsClean() {
        v2().body("{\"TenantName\":\"" + TENANT + "\"}")
                .when().post("/v2/email/tenants/delete").then().statusCode(200);

        // Only TENANT_B's association survives the cascade.
        v2().body("{\"ResourceArn\":\"" + IDENTITY_ARN + "\"}")
                .when().post("/v2/email/resources/tenants/list").then().statusCode(200)
                .body("ResourceTenants", hasSize(1))
                .body("ResourceTenants[0].TenantName", equalTo(TENANT_B));
        v2().body("{\"ResourceArn\":\"" + CONFIG_SET_ARN + "\"}")
                .when().post("/v2/email/resources/tenants/list").then().statusCode(200)
                .body("ResourceTenants", hasSize(0));

        // A recreated same-name tenant has a new TenantId and sees none of the old associations.
        v2().body("{\"TenantName\":\"" + TENANT + "\"}")
                .when().post("/v2/email/tenants").then().statusCode(200);
        v2().body("{\"TenantName\":\"" + TENANT + "\"}")
                .when().post("/v2/email/tenants/resources/list").then().statusCode(200)
                .body("TenantResources", hasSize(0));
        v2().body("{\"TenantName\":\"" + TENANT + "\"}")
                .when().post("/v2/email/tenants/delete").then().statusCode(200);
    }

    @Test
    @Order(12)
    void cleanup_afterDisassociation_backingResourcesDeletable() {
        v2().body("{\"TenantName\":\"" + TENANT_B + "\",\"ResourceArn\":\"" + IDENTITY_ARN + "\"}")
                .when().post("/v2/email/tenants/resources/delete").then().statusCode(200);
        v2().body("{\"TenantName\":\"" + TENANT_B + "\"}")
                .when().post("/v2/email/tenants/delete").then().statusCode(200);
        v2().when().delete("/v2/email/identities/" + IDENTITY).then().statusCode(200);
        v2().when().delete("/v2/email/configuration-sets/" + CONFIG_SET).then().statusCode(200);
        v2().when().delete("/v2/email/templates/" + TEMPLATE).then().statusCode(200);
    }
}
