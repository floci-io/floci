package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.CreateServiceSpecificCredentialRequest;
import software.amazon.awssdk.services.iam.model.CreateServiceSpecificCredentialResponse;
import software.amazon.awssdk.services.iam.model.CreateUserRequest;
import software.amazon.awssdk.services.iam.model.DeleteConflictException;
import software.amazon.awssdk.services.iam.model.DeleteServiceSpecificCredentialRequest;
import software.amazon.awssdk.services.iam.model.DeleteUserRequest;
import software.amazon.awssdk.services.iam.model.LimitExceededException;
import software.amazon.awssdk.services.iam.model.ListServiceSpecificCredentialsRequest;
import software.amazon.awssdk.services.iam.model.ListServiceSpecificCredentialsResponse;
import software.amazon.awssdk.services.iam.model.NoSuchEntityException;
import software.amazon.awssdk.services.iam.model.ResetServiceSpecificCredentialRequest;
import software.amazon.awssdk.services.iam.model.ResetServiceSpecificCredentialResponse;
import software.amazon.awssdk.services.iam.model.ServiceNotSupportedException;
import software.amazon.awssdk.services.iam.model.ServiceSpecificCredential;
import software.amazon.awssdk.services.iam.model.ServiceSpecificCredentialMetadata;
import software.amazon.awssdk.services.iam.model.StatusType;
import software.amazon.awssdk.services.iam.model.UpdateServiceSpecificCredentialRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Service-specific credentials driven through the AWS SDK rather than hand-written requests.
 *
 * <p>This is the check the handcrafted XML assertions cannot make: the SDK parses the response
 * against its own model, so a missing required member, a mis-named element or a timestamp in the
 * wrong format fails here even though the raw XML looked right. The shape marks CreateDate,
 * ServiceName, ServiceSpecificCredentialId, UserName and Status required.
 *
 * <p>It is also where the error types are really checked, which matters most for the unsupported
 * service: the model declares {@code ServiceNotSupportedException} on this operation and AWS sends
 * {@code NoSuchEntity} instead, so the test asserts the type AWS actually sends and that it is not
 * the modelled one.
 */
@DisplayName("IAM Service-Specific Credentials")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IamServiceSpecificCredentialTest {

    private static final String USER_NAME = "sdk-test-service-credential-user";
    private static final String CODECOMMIT = "codecommit.amazonaws.com";
    private static final String BEDROCK = "bedrock.amazonaws.com";
    /** The services whose credential is a long-term API key rather than a password. */
    private static final List<String> LONG_TERM_KEY_SERVICES = List.of(
            BEDROCK, "aws-external-anthropic.amazonaws.com", "cloudwatch.amazonaws.com",
            "logs.amazonaws.com");

    private static IamClient iam;
    private static String credentialId;

    @BeforeAll
    static void setup() {
        iam = TestFixtures.iamClient();
        cleanupUser();
        iam.createUser(CreateUserRequest.builder().userName(USER_NAME).build());
    }

    @AfterAll
    static void cleanup() {
        cleanupUser();
    }

    private static void cleanupUser() {
        if (iam == null) {
            return;
        }
        try {
            ListServiceSpecificCredentialsResponse existing = iam.listServiceSpecificCredentials(
                    ListServiceSpecificCredentialsRequest.builder().userName(USER_NAME).build());
            for (ServiceSpecificCredentialMetadata credential
                    : existing.serviceSpecificCredentials()) {
                deleteCredential(credential.serviceSpecificCredentialId());
            }
            iam.deleteUser(DeleteUserRequest.builder().userName(USER_NAME).build());
        } catch (NoSuchEntityException expected) {
            // Nothing left over from an earlier run, which is the normal case.
        }
    }

    private static ServiceSpecificCredential create(String serviceName) {
        return iam.createServiceSpecificCredential(CreateServiceSpecificCredentialRequest.builder()
                        .userName(USER_NAME)
                        .serviceName(serviceName)
                        .build())
                .serviceSpecificCredential();
    }

    private static void deleteCredential(String id) {
        iam.deleteServiceSpecificCredential(DeleteServiceSpecificCredentialRequest.builder()
                .userName(USER_NAME)
                .serviceSpecificCredentialId(id)
                .build());
    }

    @Test
    @Order(1)
    @DisplayName("CreateServiceSpecificCredential returns a parseable credential with a password")
    void create() {
        CreateServiceSpecificCredentialResponse response = iam.createServiceSpecificCredential(
                CreateServiceSpecificCredentialRequest.builder()
                        .userName(USER_NAME)
                        .serviceName(CODECOMMIT)
                        .build());

        ServiceSpecificCredential credential = response.serviceSpecificCredential();
        assertThat(credential).as("the response must carry the credential").isNotNull();
        assertThat(credential.userName()).isEqualTo(USER_NAME);
        assertThat(credential.serviceName()).isEqualTo(CODECOMMIT);
        assertThat(credential.status()).isEqualTo(StatusType.ACTIVE);
        assertThat(credential.createDate()).as("CreateDate must parse as a timestamp").isNotNull();
        assertThat(credential.servicePassword())
                .as("the password is disclosed only here").isNotNull();
        assertThat(credential.serviceUserName())
                .as("the generated user name combines the IAM user and the account")
                .startsWith(USER_NAME + "-at-");
        // serviceSpecificCredentialId is 20 to 128 word characters, and the prefix table gives ACCA.
        assertThat(credential.serviceSpecificCredentialId())
                .startsWith("ACCA")
                .matches("\\w{20,128}");
        // The password shape and the API-key shape are exclusive.
        assertThat(credential.serviceCredentialSecret()).isNull();
        assertThat(credential.expirationDate()).isNull();

        credentialId = credential.serviceSpecificCredentialId();
    }

    @Test
    @Order(2)
    @DisplayName("ListServiceSpecificCredentials returns metadata without the password")
    void list() {
        ListServiceSpecificCredentialsResponse response = iam.listServiceSpecificCredentials(
                ListServiceSpecificCredentialsRequest.builder().userName(USER_NAME).build());

        assertThat(response.serviceSpecificCredentials()).hasSize(1);
        ServiceSpecificCredentialMetadata credential = response.serviceSpecificCredentials().get(0);
        assertThat(credential.serviceSpecificCredentialId()).isEqualTo(credentialId);
        assertThat(credential.userName()).isEqualTo(USER_NAME);
        assertThat(credential.serviceName()).isEqualTo(CODECOMMIT);
        assertThat(credential.createDate()).isNotNull();
        assertThat(credential.serviceUserName()).isNotNull();
        assertThat(response.isTruncated()).isNotEqualTo(true);
        // ServiceSpecificCredentialMetadata has no accessor for either secret at all, which is the
        // model's own way of saying the list never carries one.
    }

    /**
     * Each of the four is an alias and a secret rather than a user name and a password, and each
     * takes CredentialAgeDays, which the model allows only for a long-term API key service.
     */
    @Test
    @Order(3)
    @DisplayName("Every long-term API key service gives an alias, a secret and an expiry")
    void longTermApiKeyShape() {
        for (String service : LONG_TERM_KEY_SERVICES) {
            ServiceSpecificCredential credential = iam.createServiceSpecificCredential(
                    CreateServiceSpecificCredentialRequest.builder()
                            .userName(USER_NAME)
                            .serviceName(service)
                            .credentialAgeDays(30)
                            .build())
                    .serviceSpecificCredential();

            assertThat(credential.serviceName()).isEqualTo(service);
            assertThat(credential.serviceCredentialAlias())
                    .as("the alias includes the IAM user name and a version suffix")
                    .startsWith(USER_NAME + "+v1-");
            assertThat(credential.serviceCredentialSecret())
                    .as("the secret is returned only on the create").isNotNull();
            assertThat(credential.expirationDate())
                    .as("CredentialAgeDays must come back as a parseable ExpirationDate")
                    .isNotNull()
                    .isAfter(credential.createDate());
            assertThat(credential.servicePassword()).as("not the password shape").isNull();
            assertThat(credential.serviceUserName()).isNull();

            deleteCredential(credential.serviceSpecificCredentialId());
        }
    }

    @Test
    @Order(4)
    @DisplayName("CredentialAgeDays is rejected for a service without long-term API keys")
    void credentialAgeDaysIsOnlyForLongTermKeyServices() {
        assertThatThrownBy(() -> iam.createServiceSpecificCredential(
                CreateServiceSpecificCredentialRequest.builder()
                        .userName(USER_NAME)
                        .serviceName(CODECOMMIT)
                        .credentialAgeDays(30)
                        .build()))
                .hasMessageContaining("long-term API");
    }

    @Test
    @Order(5)
    @DisplayName("UpdateServiceSpecificCredential sets the status and the SDK reads it back")
    void updateStatus() {
        iam.updateServiceSpecificCredential(UpdateServiceSpecificCredentialRequest.builder()
                .userName(USER_NAME)
                .serviceSpecificCredentialId(credentialId)
                .status(StatusType.INACTIVE)
                .build());

        ListServiceSpecificCredentialsResponse response = iam.listServiceSpecificCredentials(
                ListServiceSpecificCredentialsRequest.builder().userName(USER_NAME).build());
        assertThat(response.serviceSpecificCredentials().get(0).status())
                .as("the status must come back as a value the SDK model knows")
                .isEqualTo(StatusType.INACTIVE);

        iam.updateServiceSpecificCredential(UpdateServiceSpecificCredentialRequest.builder()
                .userName(USER_NAME)
                .serviceSpecificCredentialId(credentialId)
                .status(StatusType.ACTIVE)
                .build());
    }

    @Test
    @Order(6)
    @DisplayName("ResetServiceSpecificCredential returns a new password and keeps the identity")
    void reset() {
        ResetServiceSpecificCredentialResponse response = iam.resetServiceSpecificCredential(
                ResetServiceSpecificCredentialRequest.builder()
                        .userName(USER_NAME)
                        .serviceSpecificCredentialId(credentialId)
                        .build());

        ServiceSpecificCredential credential = response.serviceSpecificCredential();
        assertThat(credential.serviceSpecificCredentialId()).isEqualTo(credentialId);
        assertThat(credential.serviceName()).isEqualTo(CODECOMMIT);
        assertThat(credential.servicePassword())
                .as("the reset discloses the new password").isNotNull();
    }

    /**
     * The quota is per service, so filling CodeCommit's two must leave Bedrock's own two
     * available. A count taken over the user rather than the service would fail the second half.
     */
    @Test
    @Order(7)
    @DisplayName("Two credentials per service, and each service has its own allowance")
    void quotaIsPerService() {
        ServiceSpecificCredential second = create(CODECOMMIT);
        assertThat(second.serviceSpecificCredentialId()).isNotEqualTo(credentialId);
        assertThat(second.serviceUserName())
                .as("two credentials for one service cannot share a user name")
                .startsWith(USER_NAME + "+1-at-");

        assertThatThrownBy(() -> create(CODECOMMIT)).isInstanceOf(LimitExceededException.class);

        ServiceSpecificCredential otherService = create(BEDROCK);
        deleteCredential(otherService.serviceSpecificCredentialId());
        deleteCredential(second.serviceSpecificCredentialId());
    }

    /**
     * An unsupported service is {@code NoSuchEntity}, not the {@code NotSupportedService} the
     * model declares on this operation. AWS never raises the modelled one, so a client written
     * against the model and catching {@link ServiceNotSupportedException} catches nothing: the
     * assertion here is that the typed exception is the one AWS actually sends.
     */
    @Test
    @Order(8)
    @DisplayName("An unsupported service is NoSuchEntity, not the modelled ServiceNotSupported")
    void unsupportedService() {
        assertThatThrownBy(() -> create("lambda.amazonaws.com"))
                .isInstanceOf(NoSuchEntityException.class)
                .isNotInstanceOf(ServiceNotSupportedException.class)
                .hasMessageContaining("No such service lambda.amazonaws.com is supported for "
                        + "Service Specific Credentials");
    }

    @Test
    @Order(9)
    @DisplayName("A credential blocks DeleteUser until it is deleted")
    void blocksDeleteUser() {
        assertThatThrownBy(() ->
                iam.deleteUser(DeleteUserRequest.builder().userName(USER_NAME).build()))
                .isInstanceOf(DeleteConflictException.class);

        deleteCredential(credentialId);

        ListServiceSpecificCredentialsResponse response = iam.listServiceSpecificCredentials(
                ListServiceSpecificCredentialsRequest.builder().userName(USER_NAME).build());
        assertThat(response.serviceSpecificCredentials()).isEmpty();

        assertThatThrownBy(() -> deleteCredential(credentialId))
                .isInstanceOf(NoSuchEntityException.class);
    }

    @Test
    @Order(10)
    @DisplayName("UserName is optional on the list: it resolves from the signing credentials")
    void userNameIsOptionalOnTheList() {
        // Omitting UserName must reach the implied-caller resolution rather than fail for a
        // missing parameter. The fixture signs with an access key that belongs to no stored IAM
        // user, which that resolution reports as NoSuchEntity, so this is the error that proves
        // the path ran: were UserName required, it would be a ValidationError instead.
        assertThatThrownBy(() -> iam.listServiceSpecificCredentials(
                ListServiceSpecificCredentialsRequest.builder().build()))
                .isInstanceOf(NoSuchEntityException.class)
                .as("the failure must come from resolving the key, not a missing parameter")
                .hasMessageContaining("Access Key");
    }
}
