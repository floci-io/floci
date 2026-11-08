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
import software.amazon.awssdk.services.iam.model.DeleteSigningCertificateRequest;
import software.amazon.awssdk.services.iam.model.DeleteUserRequest;
import software.amazon.awssdk.services.iam.model.ListSigningCertificatesRequest;
import software.amazon.awssdk.services.iam.model.ListSigningCertificatesResponse;
import software.amazon.awssdk.services.iam.model.NoSuchEntityException;
import software.amazon.awssdk.services.iam.model.SigningCertificate;
import software.amazon.awssdk.services.iam.model.StatusType;
import software.amazon.awssdk.services.iam.model.UpdateSigningCertificateRequest;
import software.amazon.awssdk.services.iam.model.UploadSigningCertificateRequest;
import software.amazon.awssdk.services.iam.model.UploadSigningCertificateResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Signing certificates driven through the AWS SDK rather than hand-written requests.
 *
 * <p>This is the check the handcrafted XML assertions cannot make: the SDK parses the response
 * against its own model, so a missing required member, a mis-named element or a timestamp in the
 * wrong format fails here even though the raw XML looked right. The shape marks UserName,
 * CertificateId, CertificateBody and Status required, and UploadDate is its only timestamp.
 *
 * <p>Status also round-trips through the SDK's {@code StatusType} enum, so a value the model does
 * not know would surface as {@code UNKNOWN_TO_SDK_VERSION} rather than as a string that merely
 * looked right on the wire.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IamSigningCertificateTest {

    private static final String USER_NAME = "sdk-test-signing-user";

    /** A real self-signed certificate, valid to 2036, generated with openssl. */
    private static final String CERT_PEM = """

            -----BEGIN CERTIFICATE-----
            MIIDDTCCAfWgAwIBAgIUQKw8vNoVuYpX79WK4/CF2csyLBYwDQYJKoZIhvcNAQEL
            BQAwFjEUMBIGA1UEAwwLc2RrLnRlc3QuaW8wHhcNMjYxMDAzMDAxOTQ2WhcNMzYw
            OTMwMDAxOTQ2WjAWMRQwEgYDVQQDDAtzZGsudGVzdC5pbzCCASIwDQYJKoZIhvcN
            AQEBBQADggEPADCCAQoCggEBANya41O8oCtxTORSFpJGXD+8pfQDIa4zs1zyaKvf
            0OWLIRBBcOAbTRCo8vI8nziu4HOq/7RVK2lbRkd86hgvCmBW3TaTkg38AK6ynTtj
            lgxwr9DyxGHewMANtZcf/uj0ypSrgqISp7VwiM3KRjTBEEGBUK0vxqsizS4i8cUs
            5IPkwtT0teubfFBv+/Dy5PKQpYCSKGpSfioqbPO3BG2MYhxTum52xwXNpq08slCn
            kKBcboSJDkmAiy0bbjuBYd0X/zbFNiJL/e929mRucGDrCOb/xwzO3+sQQGMHOJ82
            gkM8qd2YnfaKQWjv6Ys2D2ScjhsoAIE/gk7acFEX+tZNje0CAwEAAaNTMFEwHQYD
            VR0OBBYEFNCvcqlMKI/qgcPfKhsIfpyUq/YpMB8GA1UdIwQYMBaAFNCvcqlMKI/q
            gcPfKhsIfpyUq/YpMA8GA1UdEwEB/wQFMAMBAf8wDQYJKoZIhvcNAQELBQADggEB
            AJykw94trHhwwaua0JI0mHv6YbBmUreuc1UDTzNW+yuoEGpWef3HkkNCn9XlLn2h
            EZFQRtrLvvxqFwo9P97NX/ehSsqjnuUV5zPXPaQuydd4gOWUt8Ysq0eqIqzy28Pa
            vQTrGPkPFcL3gll2lecvXZgLTJtQKSoM6VDkUO3cbSNWHeDFxr82NXGPEsRqlVTE
            bQI1BNOypYjrtkmav2jlgWD0hIT2w54SxCoHldVcACrGeU9DHdVmQueXF+9uppAN
            inCkDT+tT4RN7hcm72EKke95XQwh0IW7ibFklkapGzVPYLS1oDJg17aaI9Ob/xem
            t2joILbhDvtwYcpNPaTAIdY=
            -----END CERTIFICATE-----
            """;

    private static IamClient iam;
    private static String certificateId;

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
            ListSigningCertificatesResponse existing = iam.listSigningCertificates(
                    ListSigningCertificatesRequest.builder().userName(USER_NAME).build());
            for (SigningCertificate certificate : existing.certificates()) {
                iam.deleteSigningCertificate(DeleteSigningCertificateRequest.builder()
                        .userName(USER_NAME)
                        .certificateId(certificate.certificateId())
                        .build());
            }
            iam.deleteUser(DeleteUserRequest.builder().userName(USER_NAME).build());
        } catch (NoSuchEntityException expected) {
            // Nothing left over from an earlier run, which is the normal case.
        }
    }

    @Test
    @Order(1)
    @DisplayName("UploadSigningCertificate returns a parseable certificate with an Active status")
    void upload() {
        UploadSigningCertificateResponse response = iam.uploadSigningCertificate(
                UploadSigningCertificateRequest.builder()
                        .userName(USER_NAME)
                        .certificateBody(CERT_PEM)
                        .build());

        SigningCertificate certificate = response.certificate();
        assertNotNull(certificate, "the response must carry the certificate");
        assertEquals(USER_NAME, certificate.userName());
        assertEquals(StatusType.ACTIVE, certificate.status(),
                "a newly uploaded certificate is Active");
        assertTrue(certificate.certificateBody().contains("BEGIN CERTIFICATE"));
        assertNotNull(certificate.uploadDate(), "UploadDate must parse as a timestamp");
        assertNotNull(certificate.certificateId());
        assertTrue(certificate.certificateId().length() >= 24,
                "certificateIdType has a minimum of 24: " + certificate.certificateId());

        certificateId = certificate.certificateId();
    }

    @Test
    @Order(2)
    @DisplayName("ListSigningCertificates returns the uploaded certificate")
    void list() {
        ListSigningCertificatesResponse response = iam.listSigningCertificates(
                ListSigningCertificatesRequest.builder().userName(USER_NAME).build());

        assertEquals(1, response.certificates().size());
        SigningCertificate certificate = response.certificates().get(0);
        assertEquals(certificateId, certificate.certificateId());
        assertEquals(USER_NAME, certificate.userName());
        assertNotNull(certificate.uploadDate());
        assertTrue(response.isTruncated() == null || !response.isTruncated());
    }

    @Test
    @Order(3)
    @DisplayName("UpdateSigningCertificate sets the status and the SDK reads it back as an enum")
    void updateStatus() {
        iam.updateSigningCertificate(UpdateSigningCertificateRequest.builder()
                .userName(USER_NAME)
                .certificateId(certificateId)
                .status(StatusType.INACTIVE)
                .build());

        ListSigningCertificatesResponse response = iam.listSigningCertificates(
                ListSigningCertificatesRequest.builder().userName(USER_NAME).build());
        assertEquals(StatusType.INACTIVE, response.certificates().get(0).status(),
                "the status must come back as a value the SDK model knows");
    }

    @Test
    @Order(4)
    @DisplayName("UserName is optional: the operation resolves it from the signing credentials")
    void userNameIsOptional() {
        // Omitting UserName must reach the implied-caller resolution rather than fail for a
        // missing parameter. The fixture signs with an access key that belongs to no stored IAM
        // user, which that resolution reports as NoSuchEntity, so this is the error that proves
        // the path ran: were UserName required, it would be a ValidationError instead.
        NoSuchEntityException thrown = assertThrows(NoSuchEntityException.class,
                () -> iam.listSigningCertificates(ListSigningCertificatesRequest.builder().build()));
        assertTrue(thrown.getMessage().contains("Access Key"),
                "the failure must come from resolving the key, not from a missing parameter: "
                        + thrown.getMessage());
    }

    @Test
    @Order(5)
    @DisplayName("DeleteSigningCertificate removes it, and an unknown id is NoSuchEntity")
    void delete() {
        iam.deleteSigningCertificate(DeleteSigningCertificateRequest.builder()
                .userName(USER_NAME)
                .certificateId(certificateId)
                .build());

        ListSigningCertificatesResponse response = iam.listSigningCertificates(
                ListSigningCertificatesRequest.builder().userName(USER_NAME).build());
        assertTrue(response.certificates().isEmpty());

        assertThrows(NoSuchEntityException.class, () -> iam.deleteSigningCertificate(
                DeleteSigningCertificateRequest.builder()
                        .userName(USER_NAME)
                        .certificateId(certificateId)
                        .build()));
    }
}
