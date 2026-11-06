package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.DeleteServerCertificateRequest;
import software.amazon.awssdk.services.iam.model.GetServerCertificateRequest;
import software.amazon.awssdk.services.iam.model.GetServerCertificateResponse;
import software.amazon.awssdk.services.iam.model.ListServerCertificateTagsRequest;
import software.amazon.awssdk.services.iam.model.ListServerCertificateTagsResponse;
import software.amazon.awssdk.services.iam.model.ListServerCertificatesRequest;
import software.amazon.awssdk.services.iam.model.ListServerCertificatesResponse;
import software.amazon.awssdk.services.iam.model.NoSuchEntityException;
import software.amazon.awssdk.services.iam.model.ServerCertificateMetadata;
import software.amazon.awssdk.services.iam.model.Tag;
import software.amazon.awssdk.services.iam.model.TagServerCertificateRequest;
import software.amazon.awssdk.services.iam.model.UntagServerCertificateRequest;
import software.amazon.awssdk.services.iam.model.UpdateServerCertificateRequest;
import software.amazon.awssdk.services.iam.model.UploadServerCertificateRequest;
import software.amazon.awssdk.services.iam.model.UploadServerCertificateResponse;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Server certificates driven through the AWS SDK rather than hand-written requests.
 *
 * <p>This is the check the handcrafted XML assertions cannot make: the SDK parses the response
 * against its own model, so a missing required member, a mis-named element or a timestamp in the
 * wrong format fails here even though the raw XML looked right. {@code UploadDate} and
 * {@code Expiration} are the interesting ones, being the only timestamps in the shape.
 */
@DisplayName("IAM Server Certificates")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IamServerCertificateTest {

    private static IamClient iam;

    private static final String CERT_NAME = "sdk-test-server-cert";
    private static final String RENAMED = "sdk-test-server-cert-renamed";

    /**
     * A real self-signed certificate and its matching key, fixed rather than generated so this
     * suite needs no crypto of its own. Floci parses both and verifies the key signs what the
     * certificate verifies, so fabricated PEM would be rejected outright. Valid until 2036, which
     * is also why the expiry assertion below can be a simple "after the epoch" check.
     */
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

    private static final String KEY_PEM = """
            -----BEGIN PRIVATE KEY-----
            MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQDcmuNTvKArcUzk
            UhaSRlw/vKX0AyGuM7Nc8mir39DliyEQQXDgG00QqPLyPJ84ruBzqv+0VStpW0ZH
            fOoYLwpgVt02k5IN/ACusp07Y5YMcK/Q8sRh3sDADbWXH/7o9MqUq4KiEqe1cIjN
            ykY0wRBBgVCtL8arIs0uIvHFLOSD5MLU9LXrm3xQb/vw8uTykKWAkihqUn4qKmzz
            twRtjGIcU7pudscFzaatPLJQp5CgXG6EiQ5JgIstG247gWHdF/82xTYiS/3vdvZk
            bnBg6wjm/8cMzt/rEEBjBzifNoJDPKndmJ32ikFo7+mLNg9knI4bKACBP4JO2nBR
            F/rWTY3tAgMBAAECggEALm2PndozKGlwOhY3p2HU/NoGYDA/iKLbYxqmYlPYqlKp
            avsm1xeP1MuF0+rjxKFzXgyVQ73wBLyfsiBtQRk3WRa2+Es/AB/zAdFYC312QRh/
            PB23rHRdwx2vg8aJVrxcHUVqWRNNhf9Q32SseWJPekXNtaE6Md7BaW4TzYBY+JPK
            zJQI6sPFScZR8y1AK1fKMAUIt/C5Cdl+wwdBAXd+P1o1oh4nSmkmGO8jsljNSZ7E
            RFJ53fvvdecKuXDUOADCYFKQZGz4GieuYRPrguCLUbB27Detl5pOPYVMqTfNSQCV
            JLLbGK4JDzCFHS2IF6WpDTNY719hJesYqQ9I5HtpeQKBgQDx+rk/WSo/U11loyug
            yR2SVeDnl8NofQDo7tXW8W4kGXVnK/TceJpouAZUtNQkzk05PsK3xe65hFJACEwZ
            kQjxETR1DLaPNL/IQDSDDW4WYLJ72tjI189Ec3r9blDxwCo3tF/+WUbt1sBTPirn
            2i10KwDitd3/7ISE9zh5l2j4aQKBgQDpYx5uFseZYbu73Zq7tnyXWBZTEpM5W4q2
            /+aEj9WCT94NPlz5KX/B5xodO2BaXtlQsaq8KT+r3SnZsJs6PZwhi6nAT0Ih8m74
            VtNrqDmS5r8FXgWg+D2wGwGjA2o8FrMsHPsGa40LUfl1ibz+9HhNuqKy5C0uq7ze
            QiYvHYGY5QKBgQC6cMLQk2PgvNumVu1jifG18WNDLYSK+l18G6E5ZnXFbLQvlQep
            42adLsT5+QXHggiwBbJDpEbGO7Sfz4TK4huwmeAAG5aBilQF96o+G1fp+AEtOrZz
            MQxqoktt/tRxH+2nU9vufl0PHbq7qJeoYktJSWT6SMROzq5gQJcL4GQpmQKBgGU/
            QcFCkp6gvn+2mFzcwtopNa4ePc5BP0E6bLePanGY0lzudAkHjswAxNxvmNI7mY0f
            NlFgl6WoUXKF4iF2/teyrhami6sMcEW97QQkO63V+oKKkmiNqL1QSfp9DcY5lssJ
            W4EigVKq4kyyT1JPni0iTVjMEinQCSRmkAgahdFdAoGBAIzb9NKHv8iyoeFR4BeW
            zyyge0JmXAa2W6NvdHH5sM9U15GFS60qx74t8Mf4X7v4jajk5i2rv9rqGjyN3DDF
            SfIPqRfUDvWqSxRKJTIlT1jKopEgSvB04lBzVjQKuV5B4MmbHb1SNAmWcccLsz2g
            B9HFJ5PLRjDOwlSZnOPlmLPP
            -----END PRIVATE KEY-----
            """;

    @BeforeAll
    static void setup() {
        iam = TestFixtures.iamClient();
    }

    @AfterAll
    static void cleanup() {
        if (iam != null) {
            for (String name : new String[] {CERT_NAME, RENAMED}) {
                try {
                    iam.deleteServerCertificate(DeleteServerCertificateRequest.builder()
                            .serverCertificateName(name).build());
                } catch (Exception ignored) {
                    // Already gone, or never created because an earlier test failed.
                }
            }
        }
    }

    @Test
    @Order(1)
    @DisplayName("UploadServerCertificate returns metadata the SDK can model")
    void upload() {
        UploadServerCertificateResponse response = iam.uploadServerCertificate(
                UploadServerCertificateRequest.builder()
                        .serverCertificateName(CERT_NAME)
                        .certificateBody(CERT_PEM)
                        .privateKey(KEY_PEM)
                        .tags(Tag.builder().key("env").value("sdk-test").build())
                        .build());

        ServerCertificateMetadata metadata = response.serverCertificateMetadata();
        assertEquals(CERT_NAME, metadata.serverCertificateName());
        assertEquals("/", metadata.path());
        assertTrue(metadata.serverCertificateId().startsWith("ASCA"), metadata.serverCertificateId());
        assertTrue(metadata.arn().endsWith(":server-certificate/" + CERT_NAME), metadata.arn());
        // Both timestamps have to parse: the SDK rejects a format it does not recognise, which a
        // raw-XML assertion would have accepted.
        assertNotNull(metadata.uploadDate());
        assertNotNull(metadata.expiration());
        assertTrue(metadata.expiration().isAfter(Instant.EPOCH));
        assertEquals("env", response.tags().get(0).key());
    }

    @Test
    @Order(2)
    @DisplayName("GetServerCertificate returns the body and no private key")
    void get() {
        GetServerCertificateResponse response = iam.getServerCertificate(
                GetServerCertificateRequest.builder().serverCertificateName(CERT_NAME).build());

        assertTrue(response.serverCertificate().certificateBody().contains("BEGIN CERTIFICATE"));
        assertEquals(CERT_NAME,
                response.serverCertificate().serverCertificateMetadata().serverCertificateName());
        // The shape has no private-key member at all, so the best check is that what came back
        // carries none of it.
        assertTrue(!response.toString().contains("PRIVATE KEY"), "the private key must not be returned");
    }

    @Test
    @Order(3)
    @DisplayName("ListServerCertificates returns metadata only")
    void list() {
        ListServerCertificatesResponse response = iam.listServerCertificates(
                ListServerCertificatesRequest.builder().build());

        assertTrue(response.serverCertificateMetadataList().stream()
                .anyMatch(m -> CERT_NAME.equals(m.serverCertificateName())));
        assertNotNull(response.isTruncated());
    }

    @Test
    @Order(4)
    @DisplayName("Tags round trip through the SDK")
    void tags() {
        iam.tagServerCertificate(TagServerCertificateRequest.builder()
                .serverCertificateName(CERT_NAME)
                .tags(Tag.builder().key("owner").value("platform").build())
                .build());

        ListServerCertificateTagsResponse tags = iam.listServerCertificateTags(
                ListServerCertificateTagsRequest.builder().serverCertificateName(CERT_NAME).build());
        assertTrue(tags.tags().stream().anyMatch(t -> "owner".equals(t.key())
                && "platform".equals(t.value())));

        iam.untagServerCertificate(UntagServerCertificateRequest.builder()
                .serverCertificateName(CERT_NAME).tagKeys("owner").build());
        ListServerCertificateTagsResponse after = iam.listServerCertificateTags(
                ListServerCertificateTagsRequest.builder().serverCertificateName(CERT_NAME).build());
        assertTrue(after.tags().stream().noneMatch(t -> "owner".equals(t.key())));
    }

    @Test
    @Order(5)
    @DisplayName("UpdateServerCertificate renames and moves it")
    void update() {
        iam.updateServerCertificate(UpdateServerCertificateRequest.builder()
                .serverCertificateName(CERT_NAME)
                .newServerCertificateName(RENAMED)
                .newPath("/sdk/")
                .build());

        GetServerCertificateResponse response = iam.getServerCertificate(
                GetServerCertificateRequest.builder().serverCertificateName(RENAMED).build());
        assertEquals("/sdk/", response.serverCertificate().serverCertificateMetadata().path());
        assertTrue(response.serverCertificate().serverCertificateMetadata().arn()
                .endsWith(":server-certificate/sdk/" + RENAMED));

        assertThrows(NoSuchEntityException.class, () -> iam.getServerCertificate(
                GetServerCertificateRequest.builder().serverCertificateName(CERT_NAME).build()));
    }

    @Test
    @Order(6)
    @DisplayName("DeleteServerCertificate removes it")
    void delete() {
        iam.deleteServerCertificate(DeleteServerCertificateRequest.builder()
                .serverCertificateName(RENAMED).build());

        assertThrows(NoSuchEntityException.class, () -> iam.getServerCertificate(
                GetServerCertificateRequest.builder().serverCertificateName(RENAMED).build()));
    }
}
