package io.github.hectorvent.floci.services.paymentcryptography;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class PaymentCryptographyIntegrationTest {

    // The control plane of Payment Cryptography is an awsJson 1.0 service: the AWS SDK
    // sends this content type and targets PaymentCryptographyControlPlane.<Operation>.
    private static final String CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String TARGET = "PaymentCryptographyControlPlane.";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static Response call(String action, String body) {
        return given()
                .header("X-Amz-Target", TARGET + action)
                .contentType(CONTENT_TYPE)
                .body(body)
            .when().post("/");
    }

    private static final String CREATE_KEY = """
            {"KeyAttributes":{"KeyAlgorithm":"AES_256","KeyClass":"SYMMETRIC_KEY",
             "KeyUsage":"TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY",
             "KeyModesOfUse":{"Encrypt":true,"Decrypt":true}},
             "Exportable":true}""";

    @Test
    void aKeyIsCreatedAndReadBack() {
        String arn = call("CreateKey", CREATE_KEY)
                .then().statusCode(200)
                    .body("Key.KeyArn", matchesPattern(
                            "arn:aws:payment-cryptography:[a-z0-9-]+:[0-9]+:key/[0-9a-f]+"))
                    .body("Key.KeyState", equalTo("CREATE_COMPLETE"))
                    .body("Key.KeyCheckValue", matchesPattern("[0-9A-F]{6}"))
                    .body("Key.KeyCheckValueAlgorithm", equalTo("CMAC"))
                    .body("Key.Enabled", equalTo(true))
                    .extract().path("Key.KeyArn");

        call("GetKey", "{\"KeyIdentifier\":\"" + arn + "\"}")
                .then().statusCode(200)
                    .body("Key.KeyArn", equalTo(arn))
                    .body("Key.KeyAttributes.KeyAlgorithm", equalTo("AES_256"));
    }

    @Test
    void theKeysOfARegionAreListed() {
        String arn = call("CreateKey", CREATE_KEY).then().statusCode(200)
                .extract().path("Key.KeyArn");

        call("ListKeys", "{}")
                .then().statusCode(200)
                    .body("Keys", hasSize(greaterThanOrEqualTo(1)))
                    .body("Keys.KeyArn", hasItem(equalTo(arn)));
    }

    @Test
    void anAliasIsCreatedAndListed() {
        String arn = call("CreateKey", CREATE_KEY).then().statusCode(200)
                .extract().path("Key.KeyArn");

        call("CreateAlias", "{\"AliasName\":\"alias/team/card-key\",\"KeyArn\":\"" + arn + "\"}")
                .then().statusCode(200)
                    .body("Alias.AliasName", equalTo("alias/team/card-key"))
                    .body("Alias.KeyArn", equalTo(arn));

        call("GetAlias", "{\"AliasName\":\"alias/team/card-key\"}")
                .then().statusCode(200)
                    .body("Alias.KeyArn", equalTo(arn));

        call("ListAliases", "{}")
                .then().statusCode(200)
                    .body("Aliases.AliasName", hasItem(equalTo("alias/team/card-key")));
    }

    @Test
    void anAliasNameIsUniqueInARegion() {
        String arn = call("CreateKey", CREATE_KEY).then().statusCode(200)
                .extract().path("Key.KeyArn");
        call("CreateAlias", "{\"AliasName\":\"alias/team/duplicate\",\"KeyArn\":\"" + arn + "\"}")
                .then().statusCode(200);

        call("CreateAlias", "{\"AliasName\":\"alias/team/duplicate\",\"KeyArn\":\"" + arn + "\"}")
                .then().statusCode(400)
                    .body("__type", containsString("ConflictException"));
    }

    @Test
    void aKeyIsTaggedAndUntagged() {
        String arn = call("CreateKey", CREATE_KEY).then().statusCode(200)
                .extract().path("Key.KeyArn");

        call("TagResource", "{\"ResourceArn\":\"" + arn + "\","
                + "\"Tags\":[{\"Key\":\"Origin\",\"Value\":\"PRIMARY\"}]}")
                .then().statusCode(200);

        call("ListTagsForResource", "{\"ResourceArn\":\"" + arn + "\"}")
                .then().statusCode(200)
                    .body("Tags", hasSize(1))
                    .body("Tags.Key", contains(equalTo("Origin")))
                    .body("Tags.Value", contains(equalTo("PRIMARY")));

        call("UntagResource", "{\"ResourceArn\":\"" + arn + "\",\"TagKeys\":[\"Origin\"]}")
                .then().statusCode(200);

        call("ListTagsForResource", "{\"ResourceArn\":\"" + arn + "\"}")
                .then().statusCode(200)
                    .body("Tags", hasSize(0));
    }

    @Test
    void aKeyThatDoesNotExistIsNotFound() {
        call("GetKey", "{\"KeyIdentifier\":\"arn:aws:payment-cryptography:us-east-1:000000000000:key/"
                + "0123456789abcdef0123456789abcdef\"}")
                .then().statusCode(400)
                    .body("__type", containsString("ResourceNotFoundException"));
    }

    @Test
    void aKeyIsDeleted() {
        String arn = call("CreateKey", CREATE_KEY).then().statusCode(200)
                .extract().path("Key.KeyArn");

        call("DeleteKey", "{\"KeyIdentifier\":\"" + arn + "\"}")
                .then().statusCode(200);

        call("GetKey", "{\"KeyIdentifier\":\"" + arn + "\"}")
                .then().statusCode(400)
                    .body("__type", containsString("ResourceNotFoundException"));
    }

    @Test
    void anUnsupportedOperationIsRefused() {
        call("EncryptKey", "{}")
                .then().statusCode(400)
                    .body("__type", containsString("UnknownOperationException"));
    }

    /**
     * A key is created with the usage and the modes of use the reference
     * requires, and a request without either of them is refused.
     */
    @Test
    void aKeyWithoutItsUsageOrItsModesIsRefused() {
        call("CreateKey", "{\"KeyAttributes\":{\"KeyAlgorithm\":\"AES_256\",\"KeyClass\":\"SYMMETRIC_KEY\","
                + "\"KeyModesOfUse\":{\"Encrypt\":true}},\"Exportable\":true}")
                .then().statusCode(400)
                    .body("__type", containsString("ValidationException"));
        call("CreateKey", "{\"KeyAttributes\":{\"KeyAlgorithm\":\"AES_256\",\"KeyClass\":\"SYMMETRIC_KEY\","
                + "\"KeyUsage\":\"TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY\"},\"Exportable\":true}")
                .then().statusCode(400)
                    .body("__type", containsString("ValidationException"));
    }

    /**
     * A delete answers with the key, its state DELETE_PENDING and the moment it
     * is deleted, as the reference does.
     */
    @Test
    void aDeletedKeyIsAnsweredWithTheKey() {
        String arn = call("CreateKey", CREATE_KEY).then().statusCode(200)
                .extract().path("Key.KeyArn");

        call("DeleteKey", "{\"KeyIdentifier\":\"" + arn + "\"}")
                .then().statusCode(200)
                    .body("Key.KeyArn", equalTo(arn))
                    .body("Key.KeyState", equalTo("DELETE_PENDING"))
                    .body("Key.Enabled", equalTo(false))
                    .body("Key.DeletePendingTimestamp", notNullValue())
                    .body("Key.DeleteTimestamp", nullValue());
    }

    /**
     * The waiting period the reference offers is read, and a request naming one
     * outside it is refused.
     */
    @Test
    void aWaitingPeriodOutsideTheServiceRangeIsRefused() {
        String arn = call("CreateKey", CREATE_KEY).then().statusCode(200)
                .extract().path("Key.KeyArn");

        call("DeleteKey", "{\"KeyIdentifier\":\"" + arn + "\",\"DeleteKeyInDays\":1}")
                .then().statusCode(400)
                    .body("__type", containsString("ValidationException"));
        call("DeleteKey", "{\"KeyIdentifier\":\"" + arn + "\",\"DeleteKeyInDays\":7}")
                .then().statusCode(200);
    }

    /**
     * A listing names the state of the keys it wants, and a request naming
     * another state than the service knows is refused.
     */
    @Test
    void theKeysAreListedByTheirState() {
        String arn = call("CreateKey", CREATE_KEY).then().statusCode(200)
                .extract().path("Key.KeyArn");

        call("ListKeys", "{\"KeyState\":\"CREATE_COMPLETE\"}")
                .then().statusCode(200)
                    .body("Keys.KeyArn", hasItem(equalTo(arn)));
        call("ListKeys", "{\"KeyState\":\"DELETE_PENDING\"}")
                .then().statusCode(200)
                    .body("Keys", hasSize(0));
        call("ListKeys", "{\"KeyState\":\"NO_SUCH_STATE\"}")
                .then().statusCode(400)
                    .body("__type", containsString("ValidationException"));
    }

    /**
     * The key of an alias is optional, so an update naming no key removes the
     * key the alias names, and the alias stays.
     */
    @Test
    void anAliasIsDetachedFromItsKeyByAnUpdateNamingNoKey() {
        String arn = call("CreateKey", CREATE_KEY).then().statusCode(200)
                .extract().path("Key.KeyArn");
        call("CreateAlias", "{\"AliasName\":\"alias/team/detached\",\"KeyArn\":\"" + arn + "\"}")
                .then().statusCode(200);

        call("UpdateAlias", "{\"AliasName\":\"alias/team/detached\"}")
                .then().statusCode(200)
                    .body("Alias.AliasName", equalTo("alias/team/detached"))
                    .body("Alias.KeyArn", nullValue());

        call("GetAlias", "{\"AliasName\":\"alias/team/detached\"}")
                .then().statusCode(200)
                    .body("Alias.AliasName", equalTo("alias/team/detached"));
    }

    @Test
    void aKeyIsCreatedDisabledAndWithTags() {
        call("CreateKey", "{\"KeyAttributes\":{\"KeyAlgorithm\":\"AES_256\",\"KeyClass\":\"SYMMETRIC_KEY\","
                + "\"KeyUsage\":\"TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY\","
                + "\"KeyModesOfUse\":{\"Encrypt\":true}},\"Exportable\":false,\"Enabled\":false,"
                + "\"Tags\":[{\"Key\":\"owner\",\"Value\":\"team\"}]}")
                .then().statusCode(200)
                    .body("Key.Enabled", equalTo(false))
                    .body("Key.Exportable", equalTo(false));

        String arn = call("CreateKey", CREATE_KEY).then().statusCode(200)
                .extract().path("Key.KeyArn");

        call("ListTagsForResource", "{\"ResourceArn\":\"" + arn + "\"}")
                .then().statusCode(200)
                    .body("Tags", hasSize(0));
    }

    @Test
    void aTaggingRequestNamesItsKeyInResourceArnOnly() {
        String named = call("CreateKey", CREATE_KEY).then().statusCode(200)
                .extract().path("Key.KeyArn");
        String other = call("CreateKey", CREATE_KEY).then().statusCode(200)
                .extract().path("Key.KeyArn");

        // The request names one key and another besides it. The change follows the member
        // the operation names its key in, which is the member the permission check reads.
        call("TagResource", "{\"ResourceArn\":\"" + other + "\",\"KeyIdentifier\":\"" + named + "\","
                + "\"Tags\":[{\"Key\":\"owner\",\"Value\":\"team\"}]}")
                .then().statusCode(200);

        call("ListTagsForResource", "{\"ResourceArn\":\"" + other + "\"}")
                .then().statusCode(200)
                    .body("Tags", hasSize(1));
        call("ListTagsForResource", "{\"ResourceArn\":\"" + named + "\"}")
                .then().statusCode(200)
                    .body("Tags", hasSize(0));
    }

    /**
     * The key operations name their key by the ARN of a key, the ARN of an alias or the name of an alias, as the
     * reference takes it there, so a bare id names no key and is refused.
     */
    @Test
    void aKeyIsNamedByItsArnOrByAnAliasAndNotByABareId() {
        String arn = call("CreateKey", CREATE_KEY).then().statusCode(200)
                .extract().path("Key.KeyArn");
        String id = arn.substring(arn.lastIndexOf('/') + 1);
        call("CreateAlias", "{\"AliasName\":\"alias/team/named\",\"KeyArn\":\"" + arn + "\"}")
                .then().statusCode(200);
        String aliasArn = "arn:aws:payment-cryptography:us-east-1:000000000000:alias/team/named";

        for (String named : List.of(arn, "alias/team/named", aliasArn)) {
            call("GetKey", "{\"KeyIdentifier\":\"" + named + "\"}")
                    .then().statusCode(200)
                        .body("Key.KeyArn", equalTo(arn));
        }
        call("GetKey", "{\"KeyIdentifier\":\"" + id + "\"}")
                .then().statusCode(400)
                    .body("__type", containsString("ValidationException"));
    }

    /**
     * The model of an alias requires only its name, but the reference refuses an alias without a key, so the
     * request is refused the way it refuses it.
     */
    @Test
    void anAliasMayNotBeCreatedWithoutAKey() {
        call("CreateAlias", "{\"AliasName\":\"alias/team/reserved\"}")
                .then().statusCode(400)
                    .body("__type", containsString("ValidationException"))
                    .body("message", containsString("You can't create an alias without a key."));

        // The name was not taken by the refused request.
        String arn = call("CreateKey", CREATE_KEY).then().statusCode(200)
                .extract().path("Key.KeyArn");
        call("CreateAlias", "{\"AliasName\":\"alias/team/reserved\",\"KeyArn\":\"" + arn + "\"}")
                .then().statusCode(200)
                    .body("Alias.AliasName", equalTo("alias/team/reserved"))
                    .body("Alias.KeyArn", equalTo(arn));
    }

    /**
     * The check value of an AES key is a CMAC, which is the only algorithm this service computes, so a request
     * naming another one is refused rather than answered with a check value of another kind.
     */
    @Test
    void aCheckValueAlgorithmOtherThanCmacIsRefused() {
        call("CreateKey", "{\"KeyAttributes\":{\"KeyAlgorithm\":\"AES_256\",\"KeyClass\":\"SYMMETRIC_KEY\","
                + "\"KeyUsage\":\"TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY\","
                + "\"KeyModesOfUse\":{\"Encrypt\":true}},\"KeyCheckValueAlgorithm\":\"ANSI_X9_24\","
                + "\"Exportable\":true}")
                .then().statusCode(400)
                    .body("__type", containsString("ValidationException"));

        call("CreateKey", "{\"KeyAttributes\":{\"KeyAlgorithm\":\"AES_256\",\"KeyClass\":\"SYMMETRIC_KEY\","
                + "\"KeyUsage\":\"TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY\","
                + "\"KeyModesOfUse\":{\"Encrypt\":true}},\"KeyCheckValueAlgorithm\":\"CMAC\","
                + "\"Exportable\":true}")
                .then().statusCode(200)
                    .body("Key.KeyCheckValueAlgorithm", equalTo("CMAC"));
    }

    /**
     * The answer carries every mode of use, the ones the request did not ask for as false, so a caller reading them
     * finds a boolean for each of them.
     */
    @Test
    void everyModeOfUseIsAnswered() {
        call("CreateKey", "{\"KeyAttributes\":{\"KeyAlgorithm\":\"AES_256\",\"KeyClass\":\"SYMMETRIC_KEY\","
                + "\"KeyUsage\":\"TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY\","
                + "\"KeyModesOfUse\":{\"Encrypt\":true}},\"Exportable\":true}")
                .then().statusCode(200)
                    .body("Key.KeyAttributes.KeyModesOfUse", aMapWithSize(9))
                    .body("Key.KeyAttributes.KeyModesOfUse.Encrypt", equalTo(true))
                    .body("Key.KeyAttributes.KeyModesOfUse.Decrypt", equalTo(false))
                    .body("Key.KeyAttributes.KeyModesOfUse.NoRestrictions", equalTo(false));
    }

    /**
     * The tagging operations name their key by the ARN of one, as the reference takes it there, so a request naming
     * it by its id or by an alias names no key for the permission check and is refused.
     */
    @Test
    void aTaggingRequestNamingItsKeyByAnIdOrAnAliasIsRefused() {
        String arn = call("CreateKey", CREATE_KEY).then().statusCode(200)
                .extract().path("Key.KeyArn");
        String id = arn.substring(arn.lastIndexOf('/') + 1);
        call("CreateAlias", "{\"AliasName\":\"alias/team/tagged\",\"KeyArn\":\"" + arn + "\"}")
                .then().statusCode(200);

        for (String named : List.of(id, "alias/team/tagged")) {
            call("TagResource", "{\"ResourceArn\":\"" + named + "\","
                    + "\"Tags\":[{\"Key\":\"owner\",\"Value\":\"team\"}]}")
                    .then().statusCode(400)
                        .body("__type", containsString("ValidationException"));
        }
        call("ListTagsForResource", "{\"ResourceArn\":\"" + arn + "\"}")
                .then().statusCode(200)
                    .body("Tags", hasSize(0));
    }

    @Test
    void aTaggingRequestWithoutItsKeyIsRefused() {
        call("TagResource", "{\"KeyIdentifier\":\"abc\"}")
                .then().statusCode(400)
                    .body("__type", containsString("ValidationException"));
    }

    @Test
    void theKeysAreListedAPageAtATime() {
        call("CreateKey", CREATE_KEY).then().statusCode(200);
        call("CreateKey", CREATE_KEY).then().statusCode(200);

        String first = call("ListKeys", "{\"MaxResults\":1}").then().statusCode(200)
                .body("Keys", hasSize(1))
                .extract().path("NextToken");

        call("ListKeys", "{\"MaxResults\":1,\"NextToken\":\"" + first + "\"}")
                .then().statusCode(200)
                    .body("Keys", hasSize(1));
    }
}
