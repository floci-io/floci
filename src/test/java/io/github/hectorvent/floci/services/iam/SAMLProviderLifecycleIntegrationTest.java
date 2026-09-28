package io.github.hectorvent.floci.services.iam;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies UpdateSAMLProvider, DeleteSAMLProvider, TagSAMLProvider, UntagSAMLProvider and
 * ListSAMLProviderTags against the real wire shape and AWS's documented behavior.
 */
@QuarkusTest
class SAMLProviderLifecycleIntegrationTest {

    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request";

    private static String metadataFor(String entityId, String certBase64) {
        return "<md:EntityDescriptor xmlns:md=\"urn:oasis:names:tc:SAML:2.0:metadata\" entityID=\""
                + entityId + "\"><md:IDPSSODescriptor><md:KeyDescriptor use=\"signing\">"
                + "<ds:KeyInfo xmlns:ds=\"http://www.w3.org/2000/09/xmldsig#\"><ds:X509Data>"
                + "<ds:X509Certificate>" + certBase64 + "</ds:X509Certificate></ds:X509Data></ds:KeyInfo>"
                + "</md:KeyDescriptor></md:IDPSSODescriptor></md:EntityDescriptor>";
    }

    private static RequestSpecification iam(String action) {
        return given().header("Authorization", IAM_AUTH).formParam("Action", action);
    }

    private static String createProvider(String name, String entityId, String certBase64) {
        iam("CreateSAMLProvider")
            .formParam("Name", name)
            .formParam("SAMLMetadataDocument", metadataFor(entityId, certBase64))
        .when().post("/").then().statusCode(200);
        return "arn:aws:iam::000000000000:saml-provider/" + name;
    }

    @Test
    void updateSamlProviderReplacesTheMetadataDocument() {
        String name = "update-saml-" + System.nanoTime();
        String arn = createProvider(name, "https://idp.example.test/original", "b3JpZ2luYWw=");

        iam("UpdateSAMLProvider")
            .formParam("SAMLProviderArn", arn)
            .formParam("SAMLMetadataDocument", metadataFor("https://idp.example.test/updated", "dXBkYXRlZA=="))
        .when().post("/").then()
            .statusCode(200)
            .body("UpdateSAMLProviderResponse.UpdateSAMLProviderResult.SAMLProviderArn", equalTo(arn));

        iam("GetSAMLProvider").formParam("SAMLProviderArn", arn)
        .when().post("/").then()
            .statusCode(200)
            .body(containsString("https://idp.example.test/updated"))
            .body(containsString("dXBkYXRlZA=="))
            .body(not(containsString("https://idp.example.test/original")));
    }

    @Test
    void updateSamlProviderWithNoMetadataLeavesTheExistingDocumentUnchanged() {
        String name = "update-noop-saml-" + System.nanoTime();
        String arn = createProvider(name, "https://idp.example.test/kept", "a2VwdA==");

        iam("UpdateSAMLProvider").formParam("SAMLProviderArn", arn)
        .when().post("/").then().statusCode(200);

        iam("GetSAMLProvider").formParam("SAMLProviderArn", arn)
        .when().post("/").then()
            .statusCode(200)
            .body(containsString("https://idp.example.test/kept"));
    }

    /**
     * An omitted SAMLMetadataDocument is a no-op (above), but an explicitly empty one is
     * invalid input, the same as CreateSAMLProvider already rejects it as: the two are
     * distinguishable at the wire level (parameter absent vs. present with an empty value),
     * and only the former should be treated as "nothing to update."
     */
    @Test
    void updateSamlProviderWithAnExplicitlyEmptyMetadataDocumentReturnsInvalidInput() {
        String name = "update-empty-metadata-saml-" + System.nanoTime();
        String arn = createProvider(name, "https://idp.example.test/not-emptied", "bm90ZW1wdGllZA==");

        iam("UpdateSAMLProvider")
            .formParam("SAMLProviderArn", arn)
            .formParam("SAMLMetadataDocument", "")
        .when().post("/").then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("InvalidInput"));

        iam("GetSAMLProvider").formParam("SAMLProviderArn", arn)
        .when().post("/").then()
            .statusCode(200)
            .body(containsString("https://idp.example.test/not-emptied"));
    }

    @Test
    void updateSamlProviderWithMalformedMetadataReturnsInvalidInput() {
        String name = "update-bad-metadata-saml-" + System.nanoTime();
        String arn = createProvider(name, "https://idp.example.test/ok", "b2s=");

        iam("UpdateSAMLProvider")
            .formParam("SAMLProviderArn", arn)
            .formParam("SAMLMetadataDocument", "<not-a-valid-saml-document/>")
        .when().post("/").then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("InvalidInput"));
    }

    @Test
    void updateSamlProviderOfUnknownArnReturnsNoSuchEntity() {
        iam("UpdateSAMLProvider")
            .formParam("SAMLProviderArn", "arn:aws:iam::000000000000:saml-provider/no-such-saml-provider")
        .when().post("/").then()
            .statusCode(404)
            .body("ErrorResponse.Error.Code", equalTo("NoSuchEntity"));
    }

    @Test
    void deleteSamlProviderRemovesItFromListAndGet() {
        String name = "delete-saml-" + System.nanoTime();
        String arn = createProvider(name, "https://idp.example.test/deleted", "ZGVsZXRlZA==");

        iam("DeleteSAMLProvider").formParam("SAMLProviderArn", arn)
        .when().post("/").then().statusCode(200);

        iam("GetSAMLProvider").formParam("SAMLProviderArn", arn)
        .when().post("/").then()
            .statusCode(404)
            .body("ErrorResponse.Error.Code", equalTo("NoSuchEntity"));
    }

    /**
     * AWS documents that deleting a SAML provider does not check or update any role trust
     * policy that references it: "Any attempt to assume a role that references a non-existent
     * provider resource ARN fails" (later, on AssumeRoleWithSAML), not this call.
     */
    @Test
    void deleteSamlProviderSucceedsWhileARoleTrustPolicyStillReferencesIt() {
        String name = "referenced-saml-" + System.nanoTime();
        String arn = createProvider(name, "https://idp.example.test/referenced", "cmVmZXJlbmNlZA==");
        String roleName = "role-referencing-saml-" + System.nanoTime();
        String trustPolicy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                + "\"Principal\":{\"Federated\":\"" + arn + "\"},\"Action\":\"sts:AssumeRoleWithSAML\","
                + "\"Condition\":{\"StringEquals\":{\"SAML:aud\":\"https://signin.aws.amazon.com/saml\"}}}]}";
        iam("CreateRole").formParam("RoleName", roleName).formParam("Path", "/")
            .formParam("AssumeRolePolicyDocument", trustPolicy)
        .when().post("/").then().statusCode(200);

        iam("DeleteSAMLProvider").formParam("SAMLProviderArn", arn)
        .when().post("/").then().statusCode(200);

        // The role itself is untouched by the provider's deletion.
        iam("GetRole").formParam("RoleName", roleName)
        .when().post("/").then()
            .statusCode(200)
            .body(containsString(arn));
    }

    @Test
    void deleteSamlProviderOfUnknownArnReturnsNoSuchEntity() {
        iam("DeleteSAMLProvider")
            .formParam("SAMLProviderArn", "arn:aws:iam::000000000000:saml-provider/no-such-saml-provider")
        .when().post("/").then()
            .statusCode(404)
            .body("ErrorResponse.Error.Code", equalTo("NoSuchEntity"));
    }

    @Test
    void tagAndListSamlProviderTagsReturnsThemSortedByKey() {
        String name = "tag-saml-" + System.nanoTime();
        String arn = createProvider(name, "https://idp.example.test/tagged", "dGFnZ2Vk");

        iam("TagSAMLProvider").formParam("SAMLProviderArn", arn)
            .formParam("Tags.member.1.Key", "zebra").formParam("Tags.member.1.Value", "1")
            .formParam("Tags.member.2.Key", "alpha").formParam("Tags.member.2.Value", "2")
        .when().post("/").then().statusCode(200);

        String body = iam("ListSAMLProviderTags").formParam("SAMLProviderArn", arn)
            .when().post("/").then().statusCode(200).extract().asString();

        int alphaIndex = body.indexOf("<Key>alpha</Key>");
        int zebraIndex = body.indexOf("<Key>zebra</Key>");
        assertTrue(alphaIndex >= 0 && zebraIndex >= 0 && alphaIndex < zebraIndex,
                "expected both alpha and zebra present, alpha first, in: " + body);
    }

    @Test
    void getSamlProviderIncludesSortedTagsInline() {
        String name = "get-tagged-saml-" + System.nanoTime();
        String arn = createProvider(name, "https://idp.example.test/get-tagged", "Z2V0dGFnZ2Vk");
        iam("TagSAMLProvider").formParam("SAMLProviderArn", arn)
            .formParam("Tags.member.1.Key", "Team").formParam("Tags.member.1.Value", "platform")
        .when().post("/").then().statusCode(200);

        iam("GetSAMLProvider").formParam("SAMLProviderArn", arn)
        .when().post("/").then()
            .statusCode(200)
            .body(containsString("<Key>Team</Key>"))
            .body(containsString("<Value>platform</Value>"));
    }

    @Test
    void untagSamlProviderRemovesTheTag() {
        String name = "untag-saml-" + System.nanoTime();
        String arn = createProvider(name, "https://idp.example.test/untagged", "dW50YWdnZWQ=");
        iam("TagSAMLProvider").formParam("SAMLProviderArn", arn)
            .formParam("Tags.member.1.Key", "team").formParam("Tags.member.1.Value", "platform")
        .when().post("/").then().statusCode(200);

        iam("UntagSAMLProvider").formParam("SAMLProviderArn", arn)
            .formParam("TagKeys.member.1", "team")
        .when().post("/").then().statusCode(200);

        iam("GetSAMLProvider").formParam("SAMLProviderArn", arn)
        .when().post("/").then()
            .statusCode(200)
            .body(not(containsString("<Tags>")));
    }

    @Test
    void tagSamlProviderOfUnknownArnReturnsNoSuchEntity() {
        iam("TagSAMLProvider")
            .formParam("SAMLProviderArn", "arn:aws:iam::000000000000:saml-provider/no-such-saml-provider")
            .formParam("Tags.member.1.Key", "team").formParam("Tags.member.1.Value", "platform")
        .when().post("/").then()
            .statusCode(404)
            .body("ErrorResponse.Error.Code", equalTo("NoSuchEntity"));
    }

    /** {@code tagListType} is {@code max: 50}, checked before the resource is even resolved. */
    @Test
    void tagSamlProviderBeyondFiftyTagsInOneRequestReturnsValidationError() {
        String name = "tag-limit-saml-" + System.nanoTime();
        String arn = createProvider(name, "https://idp.example.test/tag-limit", "dGFnbGltaXQ=");

        RequestSpecification request = iam("TagSAMLProvider").formParam("SAMLProviderArn", arn);
        for (int i = 1; i <= 51; i++) {
            request.formParam("Tags.member." + i + ".Key", "key" + i)
                   .formParam("Tags.member." + i + ".Value", "value" + i);
        }
        request.when().post("/").then()
            .statusCode(400)
            .body(containsString("ValidationError"));
    }

    /** The 50-tag cap is a per-resource quota: two in-shape requests that exceed it cumulatively are rejected whole. */
    @Test
    void tagSamlProviderBeyondFiftyTagsAcrossRequestsReturnsLimitExceeded() {
        String name = "tag-quota-saml-" + System.nanoTime();
        String arn = createProvider(name, "https://idp.example.test/tag-quota", "dGFncXVvdGE=");

        RequestSpecification first = iam("TagSAMLProvider").formParam("SAMLProviderArn", arn);
        for (int i = 1; i <= 30; i++) {
            first.formParam("Tags.member." + i + ".Key", "key" + i).formParam("Tags.member." + i + ".Value", "v");
        }
        first.when().post("/").then().statusCode(200);

        RequestSpecification second = iam("TagSAMLProvider").formParam("SAMLProviderArn", arn);
        for (int i = 31; i <= 60; i++) {
            second.formParam("Tags.member." + (i - 30) + ".Key", "key" + i)
                  .formParam("Tags.member." + (i - 30) + ".Value", "v");
        }
        second.when().post("/").then()
            .statusCode(409)
            .body(containsString("LimitExceeded"));
    }

    @Test
    void createSamlProviderStoresTagsFromTheRequestAndEchoesThemSorted() {
        String name = "create-tagged-saml-" + System.nanoTime();
        String body = iam("CreateSAMLProvider")
            .formParam("Name", name)
            .formParam("SAMLMetadataDocument", metadataFor("https://idp.example.test/created", "Y3JlYXRlZA=="))
            .formParam("Tags.member.1.Key", "zebra").formParam("Tags.member.1.Value", "1")
            .formParam("Tags.member.2.Key", "alpha").formParam("Tags.member.2.Value", "2")
        .when().post("/").then().statusCode(200).extract().asString();

        int alphaIndex = body.indexOf("<Key>alpha</Key>");
        int zebraIndex = body.indexOf("<Key>zebra</Key>");
        assertTrue(alphaIndex >= 0 && zebraIndex >= 0 && alphaIndex < zebraIndex,
                "expected both alpha and zebra echoed back, alpha first, in: " + body);

        // The tags are really stored, not just reflected back on the create response.
        iam("ListSAMLProviderTags")
            .formParam("SAMLProviderArn", "arn:aws:iam::000000000000:saml-provider/" + name)
        .when().post("/").then()
            .statusCode(200)
            .body(containsString("<Key>alpha</Key>"))
            .body(containsString("<Key>zebra</Key>"));
    }

    @Test
    void createSamlProviderWithoutTagsOmitsTheTagsElement() {
        String name = "create-untagged-saml-" + System.nanoTime();
        iam("CreateSAMLProvider")
            .formParam("Name", name)
            .formParam("SAMLMetadataDocument", metadataFor("https://idp.example.test/plain", "cGxhaW4="))
        .when().post("/").then()
            .statusCode(200)
            .body(not(containsString("<Tags>")));
    }

    /** AWS: if the tag list is invalid or too long, "the entire request fails and the resource is not created". */
    @Test
    void createSamlProviderBeyondFiftyTagsFailsWithoutCreatingTheProvider() {
        String name = "create-tag-limit-saml-" + System.nanoTime();
        RequestSpecification request = iam("CreateSAMLProvider")
            .formParam("Name", name)
            .formParam("SAMLMetadataDocument", metadataFor("https://idp.example.test/too-many", "dG9vbWFueQ=="));
        for (int i = 1; i <= 51; i++) {
            request.formParam("Tags.member." + i + ".Key", "key" + i)
                   .formParam("Tags.member." + i + ".Value", "value" + i);
        }
        request.when().post("/").then()
            .statusCode(400)
            .body(containsString("ValidationError"));

        iam("GetSAMLProvider")
            .formParam("SAMLProviderArn", "arn:aws:iam::000000000000:saml-provider/" + name)
        .when().post("/").then()
            .statusCode(404)
            .body("ErrorResponse.Error.Code", equalTo("NoSuchEntity"));
    }

    /**
     * The 50-tag cap is on the members sent, not on the distinct keys they collapse to. Repeating a
     * key must not shrink a 51-member list into an accepted one, on either tagging path.
     */
    @Test
    void repeatedTagKeysDoNotSlipAnOversizedListPastTheLimit() {
        String name = "dup-key-create-saml-" + System.nanoTime();
        RequestSpecification create = iam("CreateSAMLProvider")
            .formParam("Name", name)
            .formParam("SAMLMetadataDocument", metadataFor("https://idp.example.test/dup", "ZHVw"));
        // 51 members, but only 50 distinct keys: key1 is sent twice.
        for (int i = 1; i <= 50; i++) {
            create.formParam("Tags.member." + i + ".Key", "key" + i)
                  .formParam("Tags.member." + i + ".Value", "value" + i);
        }
        create.formParam("Tags.member.51.Key", "key1").formParam("Tags.member.51.Value", "again");
        create.when().post("/").then()
            .statusCode(400)
            .body(containsString("ValidationError"));

        iam("GetSAMLProvider")
            .formParam("SAMLProviderArn", "arn:aws:iam::000000000000:saml-provider/" + name)
        .when().post("/").then()
            .statusCode(404)
            .body("ErrorResponse.Error.Code", equalTo("NoSuchEntity"));

        String tagged = "dup-key-tag-saml-" + System.nanoTime();
        String arn = createProvider(tagged, "https://idp.example.test/dup-tag", "ZHVwdGFn");
        RequestSpecification tag = iam("TagSAMLProvider").formParam("SAMLProviderArn", arn);
        for (int i = 1; i <= 50; i++) {
            tag.formParam("Tags.member." + i + ".Key", "key" + i)
               .formParam("Tags.member." + i + ".Value", "value" + i);
        }
        tag.formParam("Tags.member.51.Key", "key1").formParam("Tags.member.51.Value", "again");
        tag.when().post("/").then()
            .statusCode(400)
            .body(containsString("ValidationError"));
    }

    /**
     * Without an Authorization header there is no credential scope to resolve, so the request only
     * reaches IAM through AwsQueryController's action-name fallback. This pins the SAML entries in
     * that set: drop them and this routes nowhere.
     */
    @Test
    void samlActionsRouteToIamWithoutACredentialScope() {
        String name = "unsigned-route-saml-" + System.nanoTime();
        String arn = createProvider(name, "https://idp.example.test/unsigned", "dW5zaWduZWQ=");

        given().formParam("Action", "UpdateSAMLProvider")
            .formParam("SAMLProviderArn", arn)
            .formParam("SAMLMetadataDocument", metadataFor("https://idp.example.test/unsigned-updated", "dXBkYXRlZA=="))
        .when().post("/").then()
            .statusCode(200)
            .body(containsString("UpdateSAMLProviderResponse"));

        iam("GetSAMLProvider").formParam("SAMLProviderArn", arn)
        .when().post("/").then()
            .statusCode(200)
            .body(containsString("https://idp.example.test/unsigned-updated"));
    }
}
