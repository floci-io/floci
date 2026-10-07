package io.github.hectorvent.floci.services.iam;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The service-specific credential lifecycle over the wire.
 *
 * <p>The quota is two per service per user, so each test makes its own user and thereby owns a
 * fresh budget for each supported service.
 */
@QuarkusTest
class ServiceSpecificCredentialIntegrationTest {

    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request";
    private static final String CODECOMMIT = "codecommit.amazonaws.com";
    private static final String CASSANDRA = "cassandra.amazonaws.com";
    private static final String BEDROCK = "bedrock.amazonaws.com";
    /** The four whose credential is a long-term API key rather than a user name and password. */
    private static final List<String> LONG_TERM_KEY_SERVICES = List.of(
            BEDROCK, "aws-external-anthropic.amazonaws.com", "cloudwatch.amazonaws.com",
            "logs.amazonaws.com");
    private static final List<String> PASSWORD_SERVICES = List.of(CODECOMMIT, CASSANDRA);

    private static RequestSpecification iam(String action) {
        return given().header("Authorization", IAM_AUTH).formParam("Action", action);
    }

    private static String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }

    private static String user() {
        String name = "ssc-user-" + suffix();
        iam("CreateUser").formParam("UserName", name).when().post("/").then().statusCode(200);
        return name;
    }

    private static String create(String userName, String serviceName) {
        return iam("CreateServiceSpecificCredential")
            .formParam("UserName", userName)
            .formParam("ServiceName", serviceName)
        .when().post("/").then().statusCode(200)
            .extract().path("CreateServiceSpecificCredentialResponse"
                    + ".CreateServiceSpecificCredentialResult.ServiceSpecificCredential"
                    + ".ServiceSpecificCredentialId");
    }

    private static final String CREATE_RESULT =
            "CreateServiceSpecificCredentialResponse.CreateServiceSpecificCredentialResult"
                    + ".ServiceSpecificCredential.";

    @Test
    void createReturnsAnAccaIdAUserNameAndAPasswordForCodeCommit() {
        String userName = user();

        String id = iam("CreateServiceSpecificCredential")
            .formParam("UserName", userName)
            .formParam("ServiceName", CODECOMMIT)
        .when().post("/").then().statusCode(200)
            .body(CREATE_RESULT + "UserName", equalTo(userName))
            .body(CREATE_RESULT + "ServiceName", equalTo(CODECOMMIT))
            .body(CREATE_RESULT + "Status", equalTo("Active"))
            // The service user name AWS derives: the IAM user, then the account.
            .body(CREATE_RESULT + "ServiceUserName", startsWith(userName + "-at-"))
            .extract().path(CREATE_RESULT + "ServiceSpecificCredentialId");

        assertNotNull(id);
        assertTrue(id.startsWith("ACCA"), "the prefix table gives ACCA: " + id);
        // serviceSpecificCredentialId is 20 to 128 word characters.
        assertTrue(id.length() >= 20 && id.length() <= 128, "id length: " + id.length());
        assertTrue(id.matches("\\w+"), id);
    }

    /** The password is disclosed here and never again, so it has to be present on the create. */
    @Test
    void thePasswordIsReturnedOnlyByTheCreate() {
        String userName = user();

        String password = iam("CreateServiceSpecificCredential")
            .formParam("UserName", userName)
            .formParam("ServiceName", CODECOMMIT)
        .when().post("/").then().statusCode(200)
            .extract().path(CREATE_RESULT + "ServicePassword");
        assertNotNull(password, "the create must disclose the password");
        assertTrue(password.length() >= 20, "a real password, not a placeholder");

        // And the list, the only other reader, is defined without it.
        iam("ListServiceSpecificCredentials").formParam("UserName", userName)
        .when().post("/").then().statusCode(200)
            .body(not(containsString("ServicePassword")))
            .body(not(containsString(password)));
    }

    /**
     * A long-term API key service's credential is an alias and a secret rather than a user name
     * and a password. The model carries both shapes and a credential has exactly one of them.
     *
     * <p>The alias "includes the IAM user name and a suffix containing version and creation
     * information", so it is derived from the user rather than random.
     */
    @Test
    void everyLongTermApiKeyServiceGetsAnAliasAndASecret() {
        for (String service : LONG_TERM_KEY_SERVICES) {
            String userName = user();

            iam("CreateServiceSpecificCredential")
                .formParam("UserName", userName)
                .formParam("ServiceName", service)
            .when().post("/").then().statusCode(200)
                .body(CREATE_RESULT + "ServiceName", equalTo(service))
                .body(CREATE_RESULT + "ServiceCredentialAlias", startsWith(userName + "+v1-"))
                .body(CREATE_RESULT + "ServiceCredentialSecret", not(equalTo(null)))
                .body(not(containsString("ServiceUserName")))
                .body(not(containsString("ServicePassword")));
        }
    }

    /** The other two give a user name and a password, and never an alias or a secret. */
    @Test
    void thePasswordServicesGetAUserNameAndAPassword() {
        for (String service : PASSWORD_SERVICES) {
            String userName = user();

            iam("CreateServiceSpecificCredential")
                .formParam("UserName", userName)
                .formParam("ServiceName", service)
            .when().post("/").then().statusCode(200)
                .body(CREATE_RESULT + "ServiceName", equalTo(service))
                .body(CREATE_RESULT + "ServiceUserName", startsWith(userName + "-at-"))
                .body(CREATE_RESULT + "ServicePassword", not(equalTo(null)))
                .body(not(containsString("ServiceCredentialAlias")))
                .body(not(containsString("ServiceCredentialSecret")));
        }
    }

    /**
     * A legacy per-partition form of a supported service's principal must not be refused for a
     * service that is in fact supported, and the stored name folds to the universal form.
     */
    @Test
    void aLegacyPartitionFormOfASupportedServiceIsAccepted() {
        iam("CreateServiceSpecificCredential")
            .formParam("UserName", user())
            .formParam("ServiceName", "bedrock.amazonaws.com.cn")
        .when().post("/").then().statusCode(200)
            .body(CREATE_RESULT + "ServiceName", equalTo(BEDROCK));
    }

    /**
     * CredentialAgeDays "is only valid for services that support long-term API keys", so each of
     * those four takes it and neither password service does.
     */
    @Test
    void credentialAgeDaysSetsAnExpiryAndOnlyForLongTermKeyServices() {
        for (String service : LONG_TERM_KEY_SERVICES) {
            iam("CreateServiceSpecificCredential")
                .formParam("UserName", user())
                .formParam("ServiceName", service)
                .formParam("CredentialAgeDays", "30")
            .when().post("/").then().statusCode(200)
                .body(CREATE_RESULT + "ExpirationDate", containsString("T"));
        }

        for (String service : PASSWORD_SERVICES) {
            iam("CreateServiceSpecificCredential")
                .formParam("UserName", user())
                .formParam("ServiceName", service)
                .formParam("CredentialAgeDays", "30")
            .when().post("/").then().statusCode(400)
                .body(containsString("ValidationError"));
        }
    }

    /**
     * A present but unparseable {@code CredentialAgeDays} is a validation error, not an absent
     * one. Treating it as absent would answer a malformed request with a credential that never
     * expires, which is the opposite of what the caller asked for.
     */
    @Test
    void anEmptyOrNonNumericCredentialAgeIsRejectedRatherThanIgnored() {
        for (String days : List.of("", " ", "thirty", "30.5")) {
            iam("CreateServiceSpecificCredential")
                .formParam("UserName", user())
                .formParam("ServiceName", BEDROCK)
                .formParam("CredentialAgeDays", days)
            .when().post("/").then().statusCode(400)
                .body(containsString("ValidationError"))
                .body(containsString("credentialAgeDays"))
                .body(not(containsString("ExpirationDate")));
        }
    }

    /** credentialAgeDays is 1 to 36600, both in the model and in the User Guide's prose. */
    @Test
    void aCredentialAgeOutsideTheDocumentedRangeIsRejected() {
        for (String days : List.of("0", "-1", "36601")) {
            iam("CreateServiceSpecificCredential")
                .formParam("UserName", user())
                .formParam("ServiceName", BEDROCK)
                .formParam("CredentialAgeDays", days)
            .when().post("/").then().statusCode(400)
                .body(containsString("ValidationError"))
                .body(containsString("credentialAgeDays"));
        }

        // And both ends of the range are accepted, so the bounds are not off by one.
        for (String days : List.of("1", "36600")) {
            iam("CreateServiceSpecificCredential")
                .formParam("UserName", user())
                .formParam("ServiceName", BEDROCK)
                .formParam("CredentialAgeDays", days)
            .when().post("/").then().statusCode(200)
                .body(CREATE_RESULT + "ExpirationDate", containsString("T"));
        }
    }

    /**
     * A key with an expiry still ahead of it reports the stored status. The reported status is
     * derived from the expiry, so this is the half that catches a derivation reporting
     * {@code Expired} for everything that merely has a date.
     */
    @Test
    void aKeyWithAFutureExpiryStillReportsItsStoredStatus() {
        String userName = user();
        String id = iam("CreateServiceSpecificCredential")
            .formParam("UserName", userName)
            .formParam("ServiceName", BEDROCK)
            .formParam("CredentialAgeDays", "1")
        .when().post("/").then().statusCode(200)
            .body(CREATE_RESULT + "Status", equalTo("Active"))
            .extract().path(CREATE_RESULT + "ServiceSpecificCredentialId");

        String member = "ListServiceSpecificCredentialsResponse"
                + ".ListServiceSpecificCredentialsResult.ServiceSpecificCredentials.member.";
        iam("ListServiceSpecificCredentials").formParam("UserName", userName)
        .when().post("/").then().statusCode(200)
            .body(member + "Status", equalTo("Active"));

        iam("UpdateServiceSpecificCredential")
            .formParam("UserName", userName)
            .formParam("ServiceSpecificCredentialId", id)
            .formParam("Status", "Inactive")
        .when().post("/").then().statusCode(200);
        iam("ListServiceSpecificCredentials").formParam("UserName", userName)
        .when().post("/").then().statusCode(200)
            .body(member + "Status", equalTo("Inactive"));
    }

    /** Without it, the credential "will not expire", so no expiry is reported. */
    @Test
    void withoutCredentialAgeDaysThereIsNoExpiry() {
        String userName = user();

        iam("CreateServiceSpecificCredential")
            .formParam("UserName", userName)
            .formParam("ServiceName", BEDROCK)
        .when().post("/").then().statusCode(200)
            .body(not(containsString("ExpirationDate")));
    }

    /**
     * Only the sourced services are supported, and an unsupported one is {@code NoSuchEntity} with
     * status 404, not the {@code NotSupportedService} the model would suggest. AWS declares
     * {@code ServiceNotSupportedException} on Create and List and then does not raise it: its
     * recorded answer is this, for a nonexistent service, a malformed name and an existing but
     * unsupported service alike.
     */
    @Test
    void anUnsupportedServiceIsRefused() {
        String userName = user();

        for (String service : List.of(
                // A plausible name for a service that does not exist.
                "nonexistentservice.amazonaws.com",
                // A real service that does not support these credentials.
                "lambda.amazonaws.com", "s3.amazonaws.com",
                // Not a service principal at all.
                "o3on3n3onosneo", "codecommit")) {
            iam("CreateServiceSpecificCredential")
                .formParam("UserName", userName)
                .formParam("ServiceName", service)
            .when().post("/").then().statusCode(404)
                .body(containsString("NoSuchEntity"))
                .body(containsString("No such service " + service
                        + " is supported for Service Specific Credentials"))
                .body(not(containsString("NotSupportedService")));
        }

        // An absent or blank name never reaches that check: it fails the required-parameter one.
        iam("CreateServiceSpecificCredential")
            .formParam("UserName", userName).formParam("ServiceName", "")
        .when().post("/").then().statusCode(400).body(containsString("ValidationError"));
    }

    /** The list takes the same filter, so it rejects the same way. */
    @Test
    void theListRefusesAnUnsupportedServiceFilter() {
        iam("ListServiceSpecificCredentials")
            .formParam("UserName", user())
            .formParam("ServiceName", "nonexistentservice.amazonaws.com")
        .when().post("/").then().statusCode(404)
            .body(containsString("NoSuchEntity"))
            .body(containsString("No such service nonexistentservice.amazonaws.com "
                    + "is supported for Service Specific Credentials"));
    }

    /** A supported service the user holds nothing for is an empty list, not an error. */
    @Test
    void theListOfASupportedServiceWithNoCredentialsIsEmpty() {
        String userName = user();
        create(userName, CODECOMMIT);

        iam("ListServiceSpecificCredentials")
            .formParam("UserName", userName)
            .formParam("ServiceName", CASSANDRA)
        .when().post("/").then().statusCode(200)
            .body(not(containsString("ACCA")));
    }

    /** All six sourced services are accepted, and the quota is per service, so all six fit. */
    @Test
    void everySupportedServiceIsAccepted() {
        String userName = user();
        List<String> services = new ArrayList<>(PASSWORD_SERVICES);
        services.addAll(LONG_TERM_KEY_SERVICES);
        for (String service : services) {
            create(userName, service);
        }

        iam("ListServiceSpecificCredentials").formParam("UserName", userName)
        .when().post("/").then().statusCode(200)
            .body("ListServiceSpecificCredentialsResponse"
                    + ".ListServiceSpecificCredentialsResult.ServiceSpecificCredentials"
                    + ".member.size()", equalTo(services.size()));
    }

    /**
     * The quota is per service, not per user: "a maximum of two sets of service-specific
     * credentials for each supported service per IAM user".
     */
    @Test
    void theQuotaIsTwoPerServiceRatherThanTwoPerUser() {
        String userName = user();
        create(userName, CODECOMMIT);
        create(userName, CODECOMMIT);

        iam("CreateServiceSpecificCredential")
            .formParam("UserName", userName)
            .formParam("ServiceName", CODECOMMIT)
        .when().post("/").then().statusCode(409)
            .body(containsString("LimitExceeded"));

        // But another service still has its own budget, which is what "per service" means.
        create(userName, CASSANDRA);
        create(userName, CASSANDRA);
    }

    /**
     * Two credentials for one service are told apart by a {@code +n} in the service user name,
     * which the API Reference's examples show as {@code anika-at-<account>} and
     * {@code anika+1-at-<account>}.
     */
    @Test
    void theSecondCredentialForAServiceGetsASuffixedServiceUserName() {
        String userName = user();

        String first = iam("CreateServiceSpecificCredential")
            .formParam("UserName", userName).formParam("ServiceName", CODECOMMIT)
        .when().post("/").then().statusCode(200)
            .extract().path(CREATE_RESULT + "ServiceUserName");
        String second = iam("CreateServiceSpecificCredential")
            .formParam("UserName", userName).formParam("ServiceName", CODECOMMIT)
        .when().post("/").then().statusCode(200)
            .extract().path(CREATE_RESULT + "ServiceUserName");

        assertEquals(userName + "-at-" + "000000000000", first);
        assertEquals(userName + "+1-at-" + "000000000000", second);
        assertNotEquals(first, second, "two credentials cannot share a service user name");
    }

    /**
     * Deleting the unsuffixed credential frees its name, and the next create must take that free
     * name rather than re-mint the suffixed one that is still in use. A suffix taken from the
     * count rather than from what is free gives two live credentials the same service user name,
     * which is what the caller authenticates with.
     */
    @Test
    void recreatingAfterADeleteNeverReusesALiveServiceUserName() {
        String userName = user();
        String firstId = create(userName, CODECOMMIT);
        String secondName = iam("CreateServiceSpecificCredential")
            .formParam("UserName", userName).formParam("ServiceName", CODECOMMIT)
        .when().post("/").then().statusCode(200)
            .extract().path(CREATE_RESULT + "ServiceUserName");

        iam("DeleteServiceSpecificCredential")
            .formParam("UserName", userName)
            .formParam("ServiceSpecificCredentialId", firstId)
        .when().post("/").then().statusCode(200);

        String replacementName = iam("CreateServiceSpecificCredential")
            .formParam("UserName", userName).formParam("ServiceName", CODECOMMIT)
        .when().post("/").then().statusCode(200)
            .extract().path(CREATE_RESULT + "ServiceUserName");

        assertNotEquals(secondName, replacementName,
                "the replacement took the name of the credential still in use");
        assertEquals(userName + "-at-" + "000000000000", replacementName,
                "the freed unsuffixed name is the lowest free one");
    }

    @Test
    void theStatusCanBeSetToEveryDocumentedValue() {
        String userName = user();
        String id = create(userName, CODECOMMIT);

        for (String status : List.of("Inactive", "Expired", "Active")) {
            iam("UpdateServiceSpecificCredential")
                .formParam("UserName", userName)
                .formParam("ServiceSpecificCredentialId", id)
                .formParam("Status", status)
            .when().post("/").then().statusCode(200);

            iam("ListServiceSpecificCredentials").formParam("UserName", userName)
            .when().post("/").then().statusCode(200)
                .body("ListServiceSpecificCredentialsResponse"
                        + ".ListServiceSpecificCredentialsResult.ServiceSpecificCredentials"
                        + ".member.Status", equalTo(status));
        }
    }

    /** The reset replaces the secret and keeps everything that identifies the credential. */
    @Test
    void theResetChangesThePasswordAndNothingElse() {
        String userName = user();
        String before = iam("CreateServiceSpecificCredential")
            .formParam("UserName", userName).formParam("ServiceName", CODECOMMIT)
        .when().post("/").then().statusCode(200)
            .extract().path(CREATE_RESULT + "ServicePassword");
        String id = iam("ListServiceSpecificCredentials").formParam("UserName", userName)
        .when().post("/").then().statusCode(200)
            .extract().path("ListServiceSpecificCredentialsResponse"
                    + ".ListServiceSpecificCredentialsResult.ServiceSpecificCredentials"
                    + ".member.ServiceSpecificCredentialId");

        String reset = "ResetServiceSpecificCredentialResponse"
                + ".ResetServiceSpecificCredentialResult.ServiceSpecificCredential.";
        iam("ResetServiceSpecificCredential")
            .formParam("UserName", userName)
            .formParam("ServiceSpecificCredentialId", id)
        .when().post("/").then().statusCode(200)
            .body(reset + "ServiceSpecificCredentialId", equalTo(id))
            .body(reset + "ServiceName", equalTo(CODECOMMIT))
            .body(reset + "ServicePassword", not(equalTo(before)));
    }

    @Test
    void deleteRemovesIt() {
        String userName = user();
        String id = create(userName, CODECOMMIT);

        iam("DeleteServiceSpecificCredential")
            .formParam("UserName", userName)
            .formParam("ServiceSpecificCredentialId", id)
        .when().post("/").then().statusCode(200);

        iam("ListServiceSpecificCredentials").formParam("UserName", userName)
        .when().post("/").then().statusCode(200).body(not(containsString(id)));
    }

    /** "This parameter cannot be specified together with UserName." */
    @Test
    void allUsersCannotBeCombinedWithAUserName() {
        String userName = user();
        create(userName, CODECOMMIT);

        iam("ListServiceSpecificCredentials")
            .formParam("UserName", userName)
            .formParam("AllUsers", "true")
        .when().post("/").then().statusCode(400)
            .body(containsString("ValidationError"));
    }

    @Test
    void allUsersListsBeyondOneUser() {
        String first = user();
        String second = user();
        String firstId = create(first, CODECOMMIT);
        String secondId = create(second, CODECOMMIT);

        iam("ListServiceSpecificCredentials").formParam("AllUsers", "true")
        .when().post("/").then().statusCode(200)
            .body(containsString(firstId))
            .body(containsString(secondId));

        // And a single-user list sees only its own.
        iam("ListServiceSpecificCredentials").formParam("UserName", first)
        .when().post("/").then().statusCode(200)
            .body(containsString(firstId))
            .body(not(containsString(secondId)));
    }

    @Test
    void theListCanBeFilteredByService() {
        String userName = user();
        String codecommitId = create(userName, CODECOMMIT);
        String cassandraId = create(userName, CASSANDRA);

        iam("ListServiceSpecificCredentials")
            .formParam("UserName", userName)
            .formParam("ServiceName", CASSANDRA)
        .when().post("/").then().statusCode(200)
            .body(containsString(cassandraId))
            .body(not(containsString(codecommitId)));
    }

    @Test
    void aCredentialOfAnotherUserCannotBeTouched() {
        String owner = user();
        String other = user();
        String id = create(owner, CODECOMMIT);

        iam("UpdateServiceSpecificCredential")
            .formParam("UserName", other)
            .formParam("ServiceSpecificCredentialId", id)
            .formParam("Status", "Inactive")
        .when().post("/").then().statusCode(404).body(containsString("NoSuchEntity"));

        iam("ResetServiceSpecificCredential")
            .formParam("UserName", other)
            .formParam("ServiceSpecificCredentialId", id)
        .when().post("/").then().statusCode(404).body(containsString("NoSuchEntity"));

        iam("DeleteServiceSpecificCredential")
            .formParam("UserName", other)
            .formParam("ServiceSpecificCredentialId", id)
        .when().post("/").then().statusCode(404).body(containsString("NoSuchEntity"));
    }

    /** The model makes both of the create's parameters required, and only the create's. */
    @Test
    void theCreateRequiresBothUserNameAndServiceName() {
        iam("CreateServiceSpecificCredential").formParam("ServiceName", CODECOMMIT)
        .when().post("/").then().statusCode(400)
            .body(containsString("ValidationError")).body(containsString("userName"));

        iam("CreateServiceSpecificCredential").formParam("UserName", user())
        .when().post("/").then().statusCode(400)
            .body(containsString("ValidationError")).body(containsString("serviceName"));
    }

    /** {@code Status} is required on the update, and only takes the three enum values. */
    @Test
    void theUpdateRequiresAStatusFromTheEnum() {
        String userName = user();
        String id = create(userName, CODECOMMIT);

        for (String status : List.of("active", "Deleted", "")) {
            iam("UpdateServiceSpecificCredential")
                .formParam("UserName", userName)
                .formParam("ServiceSpecificCredentialId", id)
                .formParam("Status", status)
            .when().post("/").then().statusCode(400)
                .body(containsString("ValidationError"))
                .body(containsString("1 validation error detected"))
                .body(containsString("Member must satisfy enum value set"))
                // AWS names neither the offending value nor the permitted set. The trailing colon
                // is the tell for the set, and "Deleted" for the value.
                .body(not(containsString("enum value set:")))
                .body(not(containsString("Deleted")));
        }

        iam("UpdateServiceSpecificCredential")
            .formParam("UserName", userName)
            .formParam("ServiceSpecificCredentialId", id)
        .when().post("/").then().statusCode(400)
            .body(containsString("ValidationError"));
    }

    @Test
    void anAbsentCredentialIdIsAValidationError() {
        String userName = user();

        iam("DeleteServiceSpecificCredential").formParam("UserName", userName)
        .when().post("/").then().statusCode(400)
            .body(containsString("ValidationError"))
            .body(containsString("serviceSpecificCredentialId"));
    }

    /**
     * A malformed id and an unknown one are different mistakes, and AWS answers them differently.
     * The id is checked against the model's {@code [\w]+} before anything looks for it, so one
     * carrying hyphens is a {@code ValidationError} quoting the pattern, while an id that
     * satisfies the pattern and names no credential is {@code NoSuchEntity}. Both are recorded
     * against real AWS in LocalStack's validated IAM snapshots, for all three operations.
     */
    @Test
    void aMalformedCredentialIdIsAValidationErrorRatherThanNotFound() {
        String userName = user();
        create(userName, CODECOMMIT);

        for (String action : List.of("UpdateServiceSpecificCredential",
                "ResetServiceSpecificCredential", "DeleteServiceSpecificCredential")) {
            iam(action)
                .formParam("UserName", userName)
                .formParam("ServiceSpecificCredentialId",
                        "totally-wrong-credential-id-with-hyphens")
                .formParam("Status", "Inactive")
            .when().post("/").then().statusCode(400)
                .body(containsString("ValidationError"))
                .body(containsString("serviceSpecificCredentialId"))
                .body(containsString("Member must satisfy regular expression pattern"))
                .body(not(containsString("NoSuchEntity")));
        }
    }

    /**
     * The id's other published constraint is a length of 20 to 128. A short id satisfies
     * {@code [\w]+} and still fails, and one that breaks both constraints is reported as two
     * validation errors rather than one, which is what the counted prefix is for.
     */
    @Test
    void anIdOutsideItsPublishedLengthIsAValidationError() {
        String userName = user();
        create(userName, CODECOMMIT);

        for (String id : List.of("tooshort", "W".repeat(129))) {
            iam("DeleteServiceSpecificCredential")
                .formParam("UserName", userName)
                .formParam("ServiceSpecificCredentialId", id)
            .when().post("/").then().statusCode(400)
                .body(containsString("1 validation error detected"))
                .body(containsString("Member must have length less than or equal to 128 and "
                        + "greater than or equal to 20"))
                .body(not(containsString("NoSuchEntity")));
        }

        // Short and outside the pattern at once: both constraints are reported.
        iam("DeleteServiceSpecificCredential")
            .formParam("UserName", userName)
            .formParam("ServiceSpecificCredentialId", "has-hyphens")
        .when().post("/").then().statusCode(400)
            .body(containsString("2 validation errors detected"))
            .body(containsString("Member must have length"))
            .body(containsString("Member must satisfy regular expression pattern"));

        // And an id of exactly the minimum length is accepted as well formed, so the bound is not
        // off by one: it reaches the lookup and is reported missing rather than malformed.
        iam("DeleteServiceSpecificCredential")
            .formParam("UserName", userName)
            .formParam("ServiceSpecificCredentialId", "A".repeat(20))
        .when().post("/").then().statusCode(404)
            .body(containsString("NoSuchEntity"));
    }

    @Test
    void aWellFormedButUnknownCredentialIdIsReportedAsNoSuchCredential() {
        String userName = user();
        create(userName, CODECOMMIT);

        for (String action : List.of("UpdateServiceSpecificCredential",
                "ResetServiceSpecificCredential", "DeleteServiceSpecificCredential")) {
            iam(action)
                .formParam("UserName", userName)
                .formParam("ServiceSpecificCredentialId", "satisfiesregexbutstillinvalid")
                .formParam("Status", "Inactive")
            .when().post("/").then().statusCode(404)
                .body(containsString("NoSuchEntity"))
                .body(containsString("No such credential satisfiesregexbutstillinvalid exists"))
                .body(not(containsString("ValidationError")));
        }
    }

    /**
     * A live credential blocks {@code DeleteUser} with 409 {@code DeleteConflict}. This one has no
     * message of its own: where access keys and policies each name themselves, AWS falls back to
     * the generic referenced-objects wording here.
     */
    @Test
    void aLiveCredentialBlocksDeletingItsUser() {
        String userName = user();
        String id = create(userName, CODECOMMIT);

        iam("DeleteUser").formParam("UserName", userName)
        .when().post("/").then().statusCode(409)
            .body(containsString("DeleteConflict"))
            .body(containsString("Cannot delete entity, must remove referenced objects first."));

        // Remove the credential and the same delete succeeds, so the credential is what blocked it
        // rather than anything else the user still owns.
        iam("DeleteServiceSpecificCredential")
            .formParam("UserName", userName)
            .formParam("ServiceSpecificCredentialId", id)
        .when().post("/").then().statusCode(200);

        iam("DeleteUser").formParam("UserName", userName)
        .when().post("/").then().statusCode(200);
    }

    /**
     * Without an Authorization header the Query controller infers the service from the action name
     * alone, so these have to be in its IAM set or they fall through to SQS.
     */
    @Test
    void credentialActionsRouteViaTheActionFallbackWhenAuthHeaderAbsent() {
        for (String action : List.of("CreateServiceSpecificCredential",
                "ListServiceSpecificCredentials", "UpdateServiceSpecificCredential",
                "ResetServiceSpecificCredential", "DeleteServiceSpecificCredential")) {
            given().contentType("application/x-www-form-urlencoded")
                    .formParam("Action", action)
                    .formParam("Version", "2010-05-08")
            .when().post("/").then()
                .body(containsString("iam.amazonaws.com/doc/2010-05-08"));
        }
    }
}
