package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.CreateUserRequest;
import software.amazon.awssdk.services.iam.model.DeleteSshPublicKeyRequest;
import software.amazon.awssdk.services.iam.model.DeleteUserRequest;
import software.amazon.awssdk.services.iam.model.EncodingType;
import software.amazon.awssdk.services.iam.model.GetSshPublicKeyRequest;
import software.amazon.awssdk.services.iam.model.GetSshPublicKeyResponse;
import software.amazon.awssdk.services.iam.model.ListSshPublicKeysRequest;
import software.amazon.awssdk.services.iam.model.ListSshPublicKeysResponse;
import software.amazon.awssdk.services.iam.model.NoSuchEntityException;
import software.amazon.awssdk.services.iam.model.SSHPublicKeyMetadata;
import software.amazon.awssdk.services.iam.model.StatusType;
import software.amazon.awssdk.services.iam.model.UpdateSshPublicKeyRequest;
import software.amazon.awssdk.services.iam.model.UploadSshPublicKeyRequest;
import software.amazon.awssdk.services.iam.model.UploadSshPublicKeyResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SSH public keys driven through the AWS SDK rather than hand-written requests.
 *
 * <p>This is the check the handcrafted XML assertions cannot make: the SDK parses the response
 * against its own model, so a missing required member, a mis-named element or a timestamp in the
 * wrong format fails here even though the raw XML looked right. Two enums round-trip as well,
 * {@code StatusType} and {@code EncodingType}, so a value the model does not know would come back
 * as {@code UNKNOWN_TO_SDK_VERSION} rather than as a string that merely looked correct.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IamSshPublicKeyTest {

    private static final String USER_NAME = "sdk-test-ssh-user";

    /** A real 2048-bit RSA public key in the ssh-rsa form, generated with ssh-keygen. */
    private static final String SSH_RSA_BODY =
            "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQCw3HqlhXlIaYKbGK1G2G8Mv4o6izJVoEIG"
            + "c4x3cEZlBR9XG2l905tpn0qdEiylsJj43L91ILHOgIbhh1cmFRRozbKIIOs0hCcGbdwY9le0"
            + "4vO2ufw7OXDJcO7TzatJq/XgeArQ39WbJTO8FCAy5woufVwGGhqX8G55VNzQS7jE4/N/CuTF"
            + "jbo6m0vFQ7gCRDwYmwslrrk71C/CfBJCeqNLeBs+5moqjpZLCEnhLJHf/hHdSTV7x+DduWrB"
            + "OPejy82PUfqPBY4fi2QT/Je/NseMqEb0lCz3fxBZK9vC53dHTUHck7koWIUU9P5+KFTepZsJ"
            + "pdpBHQRI+EcEgtoGRgYX";

    private static IamClient iam;
    private static String keyId;

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
            ListSshPublicKeysResponse existing = iam.listSSHPublicKeys(
                    ListSshPublicKeysRequest.builder().userName(USER_NAME).build());
            for (SSHPublicKeyMetadata key : existing.sshPublicKeys()) {
                iam.deleteSSHPublicKey(DeleteSshPublicKeyRequest.builder()
                        .userName(USER_NAME)
                        .sshPublicKeyId(key.sshPublicKeyId())
                        .build());
            }
            iam.deleteUser(DeleteUserRequest.builder().userName(USER_NAME).build());
        } catch (NoSuchEntityException expected) {
            // Nothing left over from an earlier run, which is the normal case.
        }
    }

    @Test
    @Order(1)
    @DisplayName("UploadSSHPublicKey returns an APKA id, a fingerprint and an Active status")
    void upload() {
        UploadSshPublicKeyResponse response = iam.uploadSSHPublicKey(
                UploadSshPublicKeyRequest.builder()
                        .userName(USER_NAME)
                        .sshPublicKeyBody(SSH_RSA_BODY)
                        .build());

        assertNotNull(response.sshPublicKey(), "the response must carry the key");
        assertEquals(USER_NAME, response.sshPublicKey().userName());
        assertEquals(StatusType.ACTIVE, response.sshPublicKey().status());
        assertNotNull(response.sshPublicKey().uploadDate(), "UploadDate must parse as a timestamp");
        assertNotNull(response.sshPublicKey().fingerprint(), "Fingerprint is a required member");
        assertTrue(response.sshPublicKey().fingerprint().matches("([0-9a-f]{2}:){15}[0-9a-f]{2}"),
                "the fingerprint is colon-delimited MD5: " + response.sshPublicKey().fingerprint());

        keyId = response.sshPublicKey().sshPublicKeyId();
        assertNotNull(keyId);
        assertTrue(keyId.length() >= 20, "publicKeyIdType has a minimum of 20: " + keyId);
    }

    @Test
    @Order(2)
    @DisplayName("GetSSHPublicKey converts to the requested encoding")
    void getInBothEncodings() {
        GetSshPublicKeyResponse asPem = iam.getSSHPublicKey(GetSshPublicKeyRequest.builder()
                .userName(USER_NAME)
                .sshPublicKeyId(keyId)
                .encoding(EncodingType.PEM)
                .build());
        assertTrue(asPem.sshPublicKey().sshPublicKeyBody().contains("BEGIN PUBLIC KEY"),
                "PEM was asked for: " + asPem.sshPublicKey().sshPublicKeyBody());

        GetSshPublicKeyResponse asSsh = iam.getSSHPublicKey(GetSshPublicKeyRequest.builder()
                .userName(USER_NAME)
                .sshPublicKeyId(keyId)
                .encoding(EncodingType.SSH)
                .build());
        assertTrue(asSsh.sshPublicKey().sshPublicKeyBody().startsWith("ssh-rsa "),
                "SSH was asked for: " + asSsh.sshPublicKey().sshPublicKeyBody());

        // The same key either way, so the fingerprint cannot change with the encoding.
        assertEquals(asPem.sshPublicKey().fingerprint(), asSsh.sshPublicKey().fingerprint());
    }

    @Test
    @Order(3)
    @DisplayName("ListSSHPublicKeys returns metadata without the body or fingerprint")
    void list() {
        ListSshPublicKeysResponse response = iam.listSSHPublicKeys(
                ListSshPublicKeysRequest.builder().userName(USER_NAME).build());

        assertEquals(1, response.sshPublicKeys().size());
        SSHPublicKeyMetadata metadata = response.sshPublicKeys().get(0);
        assertEquals(keyId, metadata.sshPublicKeyId());
        assertEquals(USER_NAME, metadata.userName());
        assertEquals(StatusType.ACTIVE, metadata.status());
        assertNotNull(metadata.uploadDate(), "UploadDate is required on the metadata shape");
    }

    @Test
    @Order(4)
    @DisplayName("UpdateSSHPublicKey sets the status and the SDK reads it back as an enum")
    void updateStatus() {
        iam.updateSSHPublicKey(UpdateSshPublicKeyRequest.builder()
                .userName(USER_NAME)
                .sshPublicKeyId(keyId)
                .status(StatusType.INACTIVE)
                .build());

        ListSshPublicKeysResponse response = iam.listSSHPublicKeys(
                ListSshPublicKeysRequest.builder().userName(USER_NAME).build());
        assertEquals(StatusType.INACTIVE, response.sshPublicKeys().get(0).status(),
                "the status must come back as a value the SDK model knows");
    }

    @Test
    @Order(5)
    @DisplayName("DeleteSSHPublicKey removes it, and an unknown id is NoSuchEntity")
    void delete() {
        iam.deleteSSHPublicKey(DeleteSshPublicKeyRequest.builder()
                .userName(USER_NAME)
                .sshPublicKeyId(keyId)
                .build());

        ListSshPublicKeysResponse response = iam.listSSHPublicKeys(
                ListSshPublicKeysRequest.builder().userName(USER_NAME).build());
        assertTrue(response.sshPublicKeys().isEmpty());

        assertThrows(NoSuchEntityException.class, () -> iam.deleteSSHPublicKey(
                DeleteSshPublicKeyRequest.builder()
                        .userName(USER_NAME)
                        .sshPublicKeyId(keyId)
                        .build()));
    }
}
