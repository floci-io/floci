package io.github.hectorvent.floci.services.verifiedpermissions;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.matchesPattern;

@QuarkusTest
class VerifiedPermissionsIntegrationTest {
    private static final String CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=111122223333/20260101/us-east-1/verifiedpermissions/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void policyStoreSchemaTagsAndAliases_followAwsJson10Contract() {
        String policyStoreId = createStore("OFF", "DISABLED");

        givenAws("GetPolicyStore", "{\"policyStoreId\":\"" + policyStoreId + "\",\"tags\":true}")
                .then().statusCode(200)
                .body("policyStoreId", equalTo(policyStoreId))
                .body("arn", matchesPattern("arn:aws:verifiedpermissions:us-east-1:111122223333:policy-store/PS[A-Za-z0-9]+"))
                .body("validationSettings.mode", equalTo("OFF"))
                .body("cedarVersion", equalTo("CEDAR_4"))
                .body("deletionProtection", equalTo("DISABLED"));

        String schema = "{\\\"Demo\\\":{\\\"entityTypes\\\":{},\\\"actions\\\":{}}}";
        givenAws("PutSchema", "{\"policyStoreId\":\"" + policyStoreId + "\",\"definition\":{\"cedarJson\":\"" + schema + "\"}}")
                .then().statusCode(200).body("namespaces[0]", equalTo("Demo"));
        givenAws("GetSchema", "{\"policyStoreId\":\"" + policyStoreId + "\"}")
                .then().statusCode(200).body("policyStoreId", equalTo(policyStoreId));

        String arn = givenAws("GetPolicyStore", "{\"policyStoreId\":\"" + policyStoreId + "\"}")
                .then().statusCode(200).extract().path("arn");
        givenAws("TagResource", "{\"resourceArn\":\"" + arn + "\",\"tags\":{\"project\":\"demo\"}}")
                .then().statusCode(200);
        givenAws("ListTagsForResource", "{\"resourceArn\":\"" + arn + "\"}")
                .then().statusCode(200).body("tags", hasEntry("project", "demo"));

        String alias = "policy-store-alias/demo-" + policyStoreId.substring(policyStoreId.length() - 6);
        givenAws("CreatePolicyStoreAlias", "{\"aliasName\":\"" + alias + "\",\"policyStoreId\":\"" + policyStoreId + "\"}")
                .then().statusCode(200).body("aliasName", equalTo(alias));
        givenAws("GetPolicyStore", "{\"policyStoreId\":\"" + alias + "\"}")
                .then().statusCode(200).body("policyStoreId", equalTo(policyStoreId));

        givenAws("DeletePolicyStoreAlias", "{\"aliasName\":\"" + alias + "\"}")
                .then().statusCode(200);
        givenAws("GetPolicyStoreAlias", "{\"aliasName\":\"" + alias + "\"}")
                .then().statusCode(200).body("state", equalTo("PendingDeletion"));
        givenAws("GetPolicyStore", "{\"policyStoreId\":\"" + alias + "\"}")
                .then().statusCode(400).body("__type", containsString("ResourceNotFoundException"));

        givenAws("DeletePolicyStoreAlias", "{\"aliasName\":\"" + alias + "\",\"deletionMode\":\"HardDelete\"}")
                .then().statusCode(200);
        givenAws("GetPolicyStoreAlias", "{\"aliasName\":\"" + alias + "\"}")
                .then().statusCode(400).body("__type", containsString("ResourceNotFoundException"));
    }

    @Test
    void deletionProtectionAndDeleteAliasRestrictions_matchAwsBehavior() {
        String protectedId = createStore("OFF", "ENABLED");
        givenAws("DeletePolicyStore", "{\"policyStoreId\":\"" + protectedId + "\"}")
                .then().statusCode(400).body("__type", containsString("InvalidStateException"));

        String ordinary = createStore("OFF", "DISABLED");
        String alias = "policy-store-alias/delete-check-" + ordinary.substring(ordinary.length() - 6);
        givenAws("CreatePolicyStoreAlias", "{\"aliasName\":\"" + alias + "\",\"policyStoreId\":\"" + ordinary + "\"}")
                .then().statusCode(200);
        givenAws("DeletePolicyStore", "{\"policyStoreId\":\"" + alias + "\"}")
                .then().statusCode(400).body("__type", containsString("ValidationException"));
        givenAws("DeletePolicyStore", "{\"policyStoreId\":\"" + ordinary + "\"}")
                .then().statusCode(200);
        givenAws("GetPolicyStoreAlias", "{\"aliasName\":\"" + alias + "\"}")
                .then().statusCode(400).body("__type", containsString("ResourceNotFoundException"));
    }

    @Test
    void protocolRejectsWrongValidationSettingsInvalidSchemasAndUnknownActions() {
        givenAws("CreatePolicyStore", "{\"validationSettings\":{\"mode\":\"INVALID\"}}")
                .then().statusCode(400).body("__type", containsString("ValidationException"));

        String store = createStore("OFF", "DISABLED");
        String malformedCedarSchema = "{\"Demo\":{\"entityTypes\":[]}}";
        givenAws("PutSchema", objectJson(Map.of(
                        "policyStoreId", store,
                        "definition", Map.of("cedarJson", malformedCedarSchema))))
                .then().statusCode(400).body("__type", containsString("ValidationException"));

        givenAws("DoesNotExist", "{}")
                .then().statusCode(400).body("__type", containsString("UnknownOperationException"));
    }

    private static String createStore(String validationMode, String deletionProtection) {
        return givenAws("CreatePolicyStore", "{\"validationSettings\":{\"mode\":\"" + validationMode
                + "\"},\"deletionProtection\":\"" + deletionProtection + "\"}")
                .then().statusCode(200).extract().path("policyStoreId");
    }

    private static io.restassured.response.Response givenAws(String action, String body) {
        return given().contentType(CONTENT_TYPE).header("Authorization", AUTH)
                .header("X-Amz-Target", "VerifiedPermissions." + action).body(body)
                .when().post("/");
    }
    @Test
    void staticAndTemplateLinkedPolicies_supportLifecycleNamesAndFilters() {
        String store = createStore("OFF", "DISABLED");
        String staticStatement = "permit(principal, action == Demo::Action::\"read\", resource);";
        String createStatic = objectJson(Map.of(
                "policyStoreId", store,
                "name", "name/read-all",
                "definition", Map.of("static", Map.of("statement", staticStatement, "description", "reader"))));
        String policyId = givenAws("CreatePolicy", createStatic).then().statusCode(200)
                .body("policyType", equalTo("STATIC")).body("effect", equalTo("Permit"))
                .body("actions[0].actionType", equalTo("Demo::Action"))
                .body("actions[0].actionId", equalTo("read"))
                .extract().path("policyId");
        givenAws("GetPolicy", objectJson(Map.of("policyStoreId", store, "policyId", "name/read-all")))
                .then().statusCode(200).body("policyId", equalTo(policyId))
                .body("actions[0].actionId", equalTo("read"))
                .body("definition.static.statement", equalTo(staticStatement));

        String templateStatement = "permit(principal == ?principal, action == Demo::Action::\"write\", resource == ?resource);";
        String templateId = givenAws("CreatePolicyTemplate", objectJson(Map.of(
                "policyStoreId", store, "name", "name/writer", "statement", templateStatement)))
                .then().statusCode(200).extract().path("policyTemplateId");
        String linkedBody = objectJson(Map.of(
                "policyStoreId", store,
                "definition", Map.of("templateLinked", Map.of(
                        "policyTemplateId", templateId,
                        "principal", Map.of("entityType", "Demo::User", "entityId", "alice"),
                        "resource", Map.of("entityType", "Demo::Document", "entityId", "doc-1")))));
        String linkedId = givenAws("CreatePolicy", linkedBody).then().statusCode(200)
                .body("policyType", equalTo("TEMPLATE_LINKED")).extract().path("policyId");

        givenAws("ListPolicies", objectJson(Map.of("policyStoreId", store,
                        "filter", Map.of("policyType", "TEMPLATE_LINKED"))))
                .then().statusCode(200).body("policies.size()", equalTo(1))
                .body("policies[0].policyId", equalTo(linkedId));

        givenAws("DeletePolicyTemplate", objectJson(Map.of("policyStoreId", store, "policyTemplateId", templateId)))
                .then().statusCode(200);
        givenAws("GetPolicy", objectJson(Map.of("policyStoreId", store, "policyId", linkedId)))
                .then().statusCode(400).body("__type", containsString("ResourceNotFoundException"));
    }

    @Test
    void strictStoreRejectsPoliciesUntilSchemaExists_andUpdateCannotChangeEffect() {
        String store = createStore("STRICT", "DISABLED");
        String permit = "permit(principal, action, resource);";
        givenAws("CreatePolicy", objectJson(Map.of("policyStoreId", store,
                        "definition", Map.of("static", Map.of("statement", permit)))))
                .then().statusCode(400).body("__type", containsString("ValidationException"));

        String strictSchema = "{\"Demo\":{\"entityTypes\":{\"User\":{\"shape\":{\"type\":\"Record\",\"attributes\":{}}},\"Document\":{\"shape\":{\"type\":\"Record\",\"attributes\":{}}}},\"actions\":{\"read\":{\"appliesTo\":{\"principalTypes\":[\"User\"],\"resourceTypes\":[\"Document\"]}}}}}";
        givenAws("PutSchema", objectJson(Map.of("policyStoreId", store,
                        "definition", Map.of("cedarJson", strictSchema))))
                .then().statusCode(200);
        String scopedPermit = "permit(principal, action == Demo::Action::\"read\", resource);";
        String id = givenAws("CreatePolicy", objectJson(Map.of("policyStoreId", store,
                        "definition", Map.of("static", Map.of("statement", scopedPermit)))))
                .then().statusCode(200).extract().path("policyId");
        givenAws("UpdatePolicy", objectJson(Map.of("policyStoreId", store, "policyId", id,
                        "definition", Map.of("static", Map.of("statement", "forbid(principal, action == Demo::Action::\"read\", resource);")))))
                .then().statusCode(400).body("__type", containsString("ValidationException"));
    }

    @Test
    void isAuthorized_evaluatesCedarJsonEntitiesAndDenyOverridesPermit() {
        String store = createStore("OFF", "DISABLED");
        String permit = "permit(principal, action == Demo::Action::\"read\", resource) when { principal.tenant == resource.tenant };";
        String forbid = "forbid(principal, action, resource) when { resource.blocked };";
        givenAws("CreatePolicy", objectJson(Map.of("policyStoreId", store, "definition", Map.of("static", Map.of("statement", permit)))))
                .then().statusCode(200);
        givenAws("CreatePolicy", objectJson(Map.of("policyStoreId", store, "definition", Map.of("static", Map.of("statement", forbid)))))
                .then().statusCode(200);

        String entities = "[{\"uid\":{\"type\":\"Demo::User\",\"id\":\"alice\"},\"attrs\":{\"tenant\":\"t1\"},\"parents\":[]},{\"uid\":{\"type\":\"Demo::Document\",\"id\":\"doc1\"},\"attrs\":{\"tenant\":\"t1\",\"blocked\":false},\"parents\":[]}]";
        Map<String,Object> auth = Map.of(
                "policyStoreId", store,
                "principal", Map.of("entityType", "Demo::User", "entityId", "alice"),
                "action", Map.of("actionType", "Demo::Action", "actionId", "read"),
                "resource", Map.of("entityType", "Demo::Document", "entityId", "doc1"),
                "entities", Map.of("cedarJson", entities));
        givenAws("IsAuthorized", objectJson(auth)).then().statusCode(200)
                .body("decision", equalTo("ALLOW"))
                .body("determiningPolicies.size()", equalTo(1));

        String blocked = entities.replace("\"blocked\":false", "\"blocked\":true");
        java.util.LinkedHashMap<String,Object> blockedAuth = new java.util.LinkedHashMap<>(auth);
        blockedAuth.put("entities", Map.of("cedarJson", blocked));
        givenAws("IsAuthorized", objectJson(blockedAuth)).then().statusCode(200)
                .body("decision", equalTo("DENY"))
                .body("determiningPolicies.size()", equalTo(1));
    }

    @Test
    void isAuthorized_supportsEntityListHierarchyAndBatchValidation() {
        String store = createStore("OFF", "DISABLED");
        String permit = "permit(principal in Demo::Group::\"admins\", action == Demo::Action::\"write\", resource);";
        givenAws("CreatePolicy", objectJson(Map.of("policyStoreId", store, "definition", Map.of("static", Map.of("statement", permit)))))
                .then().statusCode(200);
        Map<String,Object> principal = Map.of("entityType", "Demo::User", "entityId", "alice");
        Map<String,Object> resource = Map.of("entityType", "Demo::Document", "entityId", "doc1");
        Map<String,Object> action = Map.of("actionType", "Demo::Action", "actionId", "write");
        Map<String,Object> entity = Map.of(
                "identifier", principal,
                "parents", java.util.List.of(Map.of("entityType", "Demo::Group", "entityId", "admins")),
                "attributes", Map.of("department", Map.of("string", "platform")));
        Map<String,Object> auth = Map.of("policyStoreId", store, "principal", principal, "action", action, "resource", resource,
                "entities", Map.of("entityList", java.util.List.of(entity)));
        givenAws("IsAuthorized", objectJson(auth)).then().statusCode(200).body("decision", equalTo("ALLOW"));

        Map<String,Object> item1 = Map.of("principal", principal, "action", action, "resource", resource);
        Map<String,Object> item2 = Map.of("principal", principal,
                "action", Map.of("actionType", "Demo::Action", "actionId", "read"),
                "resource", Map.of("entityType", "Demo::Document", "entityId", "doc2"));
        givenAws("BatchIsAuthorized", objectJson(Map.of("policyStoreId", store, "requests", java.util.List.of(item1, item2),
                        "entities", Map.of("entityList", java.util.List.of(entity)))))
                .then().statusCode(200).body("results.size()", equalTo(2))
                .body("results[0].decision", equalTo("ALLOW"))
                .body("results[1].decision", equalTo("DENY"));
    }

    @Test
    void createOperationsHonorEightHourClientTokenContract() {
        String token = "token-123456";
        String body = objectJson(Map.of("clientToken", token, "validationSettings", Map.of("mode", "OFF")));
        String first = givenAws("CreatePolicyStore", body).then().statusCode(200).extract().path("policyStoreId");
        String second = givenAws("CreatePolicyStore", body).then().statusCode(200).extract().path("policyStoreId");
        org.junit.jupiter.api.Assertions.assertEquals(first, second);
        givenAws("CreatePolicyStore", objectJson(Map.of("clientToken", token,
                        "validationSettings", Map.of("mode", "STRICT"))))
                .then().statusCode(400).body("__type", containsString("ConflictException"));
    }

    @Test
    void identitySourcesSupportCognitoAndOidcLifecycleAndFiltering() {
        String store = createStore("OFF", "DISABLED");
        String cognitoArn = "arn:aws:cognito-idp:us-east-1:111122223333:userpool/us-east-1_example";
        String cognitoCreate = objectJson(Map.of(
                "policyStoreId", store,
                "principalEntityType", "Demo::User",
                "configuration", Map.of("cognitoUserPoolConfiguration", Map.of(
                        "userPoolArn", cognitoArn,
                        "clientIds", java.util.List.of("client-1"),
                        "groupConfiguration", Map.of("groupEntityType", "Demo::Group")))));
        String cognitoId = givenAws("CreateIdentitySource", cognitoCreate).then().statusCode(200)
                .extract().path("identitySourceId");
        givenAws("GetIdentitySource", objectJson(Map.of("policyStoreId", store, "identitySourceId", cognitoId)))
                .then().statusCode(200)
                .body("principalEntityType", equalTo("Demo::User"))
                .body("details.openIdIssuer", equalTo("COGNITO"))
                .body("configuration.cognitoUserPoolConfiguration.issuer", equalTo("https://cognito-idp.us-east-1.amazonaws.com/us-east-1_example"));

        String oidcCreate = objectJson(Map.of(
                "policyStoreId", store,
                "principalEntityType", "Demo::ExternalUser",
                "configuration", Map.of("openIdConnectConfiguration", Map.of(
                        "issuer", "https://id.example.com",
                        "entityIdPrefix", "ext",
                        "tokenSelection", Map.of("identityTokenOnly", Map.of(
                                "clientIds", java.util.List.of("web-client"), "principalIdClaim", "sub"))))));
        String oidcId = givenAws("CreateIdentitySource", oidcCreate).then().statusCode(200)
                .extract().path("identitySourceId");
        givenAws("ListIdentitySources", objectJson(Map.of("policyStoreId", store,
                        "filters", java.util.List.of(Map.of("principalEntityType", "Demo::ExternalUser")))))
                .then().statusCode(200).body("identitySources.size()", equalTo(1))
                .body("identitySources[0].identitySourceId", equalTo(oidcId));

        String update = objectJson(Map.of(
                "policyStoreId", store,
                "identitySourceId", oidcId,
                "principalEntityType", "Demo::Partner",
                "updateConfiguration", Map.of("openIdConnectConfiguration", Map.of(
                        "issuer", "https://id2.example.com",
                        "entityIdPrefix", "partner",
                        "tokenSelection", Map.of("accessTokenOnly", Map.of(
                                "audiences", java.util.List.of("api://demo"), "principalIdClaim", "sub"))))));
        givenAws("UpdateIdentitySource", update).then().statusCode(200);
        givenAws("GetIdentitySource", objectJson(Map.of("policyStoreId", store, "identitySourceId", oidcId)))
                .then().statusCode(200).body("principalEntityType", equalTo("Demo::Partner"))
                .body("configuration.openIdConnectConfiguration.issuer", equalTo("https://id2.example.com"));
        givenAws("DeleteIdentitySource", objectJson(Map.of("policyStoreId", store, "identitySourceId", oidcId)))
                .then().statusCode(200);
        givenAws("GetIdentitySource", objectJson(Map.of("policyStoreId", store, "identitySourceId", oidcId)))
                .then().statusCode(400).body("__type", containsString("ResourceNotFoundException"));
    }

    @Test
    void deletePolicyAndPolicyStore_areIdempotentForRetries() {
        String store = createStore("OFF", "DISABLED");
        String policy = givenAws("CreatePolicy", objectJson(Map.of(
                        "policyStoreId", store,
                        "definition", Map.of("static", Map.of(
                                "statement", "permit(principal, action, resource);")))))
                .then().statusCode(200).extract().path("policyId");

        givenAws("DeletePolicy", objectJson(Map.of("policyStoreId", store, "policyId", policy)))
                .then().statusCode(200);
        givenAws("DeletePolicy", objectJson(Map.of("policyStoreId", store, "policyId", policy)))
                .then().statusCode(200);
        givenAws("DeletePolicyStore", objectJson(Map.of("policyStoreId", store)))
                .then().statusCode(200);
        givenAws("DeletePolicyStore", objectJson(Map.of("policyStoreId", store)))
                .then().statusCode(200);
    }

    @Test
    void updatePolicy_preservesProtectedScopeAndAllowsNameOnlyUpdate() {
        String store = createStore("OFF", "DISABLED");
        String original = "permit(principal == Demo::User::\"alice\", action == Demo::Action::\"read\", resource == Demo::Document::\"doc-1\");";
        String policyId = givenAws("CreatePolicy", objectJson(Map.of(
                        "policyStoreId", store,
                        "definition", Map.of("static", Map.of("statement", original)))))
                .then().statusCode(200)
                .body("principal.entityType", equalTo("Demo::User"))
                .body("principal.entityId", equalTo("alice"))
                .body("resource.entityType", equalTo("Demo::Document"))
                .body("resource.entityId", equalTo("doc-1"))
                .extract().path("policyId");

        givenAws("UpdatePolicy", objectJson(Map.of("policyStoreId", store, "policyId", policyId,
                        "name", "name/alice-reader")))
                .then().statusCode(200).body("policyId", equalTo(policyId));

        String changedPrincipal = "permit(principal == Demo::User::\"bob\", action == Demo::Action::\"read\", resource == Demo::Document::\"doc-1\");";
        givenAws("UpdatePolicy", objectJson(Map.of("policyStoreId", store, "policyId", policyId,
                        "definition", Map.of("static", Map.of("statement", changedPrincipal)))))
                .then().statusCode(400).body("__type", containsString("ValidationException"));
    }

    @Test
    void identitySourceDocumentedCollectionLimits_areEnforced() {
        String store = createStore("OFF", "DISABLED");
        java.util.List<String> emptyAudiences = java.util.List.of();
        givenAws("CreateIdentitySource", objectJson(Map.of(
                        "policyStoreId", store,
                        "principalEntityType", "Demo::User",
                        "configuration", Map.of("openIdConnectConfiguration", Map.of(
                                "issuer", "https://id.example.com",
                                "tokenSelection", Map.of("accessTokenOnly", Map.of(
                                        "audiences", emptyAudiences)))))))
                .then().statusCode(400).body("__type", containsString("ValidationException"));
    }

    private static String objectJson(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

}
