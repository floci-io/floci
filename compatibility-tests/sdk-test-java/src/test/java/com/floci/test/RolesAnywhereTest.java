package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.rolesanywhere.RolesAnywhereClient;
import software.amazon.awssdk.services.rolesanywhere.model.AttributeMapping;
import software.amazon.awssdk.services.rolesanywhere.model.CertificateField;
import software.amazon.awssdk.services.rolesanywhere.model.CrlDetail;
import software.amazon.awssdk.services.rolesanywhere.model.MappingRule;
import software.amazon.awssdk.services.rolesanywhere.model.NotificationEvent;
import software.amazon.awssdk.services.rolesanywhere.model.NotificationSettingDetail;
import software.amazon.awssdk.services.rolesanywhere.model.ProfileDetail;
import software.amazon.awssdk.services.rolesanywhere.model.ResourceNotFoundException;
import software.amazon.awssdk.services.rolesanywhere.model.Tag;
import software.amazon.awssdk.services.rolesanywhere.model.TrustAnchorDetail;
import software.amazon.awssdk.services.rolesanywhere.model.TrustAnchorType;
import software.amazon.awssdk.services.rolesanywhere.model.ValidationException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("IAM Roles Anywhere")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RolesAnywhereTest {

    /** A fixed EC P-256 test root, valid for a hundred years, and an empty CRL it signed. */
    private static final String CA_PEM = """
            -----BEGIN CERTIFICATE-----
            MIIBwTCCAWegAwIBAgIUD3JHnuHRiTShjlcPjMIvt2sMyFYwCgYIKoZIzj0EAwIw
            LTEbMBkGA1UEAwwSRmxvY2kgVGVzdCBSb290IENBMQ4wDAYDVQQKDAVGbG9jaTAg
            Fw0yNjExMjcwOTI3NDJaGA8yMTI2MTEwMzA5Mjc0MlowLTEbMBkGA1UEAwwSRmxv
            Y2kgVGVzdCBSb290IENBMQ4wDAYDVQQKDAVGbG9jaTBZMBMGByqGSM49AgEGCCqG
            SM49AwEHA0IABFKXI0ucyB/8snrNgxlhJ28pjJoy9WMtntQG/v6Tmj79d04uERJs
            oftCvw+xAzvmy8/Irc3leVB3+/snv5w0SoyjYzBhMB0GA1UdDgQWBBST3W07p8PL
            o7CMtR39TfbFkiQDhzAfBgNVHSMEGDAWgBST3W07p8PLo7CMtR39TfbFkiQDhzAP
            BgNVHRMBAf8EBTADAQH/MA4GA1UdDwEB/wQEAwIBBjAKBggqhkjOPQQDAgNIADBF
            AiEA+wAG4UodsKFZKzzwmNJjf7ahRQnZ1mUquqsunf5vJoACICEIbdzHzvi9HQ4x
            JU3gHpx30DS4USYVNBulmE03K+WG
            -----END CERTIFICATE-----
            """;

    private static final String CRL_PEM = """
            -----BEGIN X509 CRL-----
            MIHHMG8CAQEwCgYIKoZIzj0EAwIwLTEbMBkGA1UEAwwSRmxvY2kgVGVzdCBSb290
            IENBMQ4wDAYDVQQKDAVGbG9jaRcNMjYxMTI3MDkyNzQyWhgPMjEyNjExMDMwOTI3
            NDJaoA8wDTALBgNVHRQEBAICEAAwCgYIKoZIzj0EAwIDSAAwRQIhAM478bBuBcwa
            9dnB4STiqTp6vo43FriThj44Giv0AoTBAiA4v14JlxRGf23PKyc+is02RkkVVr3h
            6QJLJKmJtyaI7g==
            -----END X509 CRL-----
            """;

    private static final String ACCOUNT = "000000000000";

    private static RolesAnywhereClient client;
    private static TrustAnchorDetail anchor;
    private static ProfileDetail profile;
    private static CrlDetail crl;

    @BeforeAll
    static void setup() {
        client = TestFixtures.rolesAnywhereClient();
    }

    @AfterAll
    static void cleanup() {
        if (client == null) {
            return;
        }
        if (profile != null) {
            try {
                client.deleteProfile(r -> r.profileId(profile.profileId()));
            } catch (RuntimeException e) {
                System.out.println("Roles Anywhere cleanup skipped profile: " + e);
            }
        }
        if (anchor != null) {
            try {
                client.deleteTrustAnchor(r -> r.trustAnchorId(anchor.trustAnchorId()));
            } catch (RuntimeException e) {
                System.out.println("Roles Anywhere cleanup skipped trust anchor: " + e);
            }
        }
        client.close();
    }

    @Test
    @Order(1)
    @DisplayName("CreateTrustAnchor returns the anchor with its ARN, timestamps and default notification settings")
    void createTrustAnchor() {
        anchor = client.createTrustAnchor(r -> r.name("sdk-test-anchor")
                .enabled(true)
                .aliasName("sdk-test-anchor")
                .source(s -> s.sourceType(TrustAnchorType.CERTIFICATE_BUNDLE)
                        .sourceData(d -> d.x509CertificateData(CA_PEM)))
                .tags(Tag.builder().key("team").value("compat").build())).trustAnchor();

        assertThat(anchor.trustAnchorArn()).contains(":rolesanywhere:").contains(":trust-anchor/" + anchor.trustAnchorId());
        assertThat(anchor.aliasArn()).endsWith(":alias/trust-anchor/sdk-test-anchor");
        assertThat(anchor.enabled()).isTrue();
        assertThat(anchor.createdAt()).isAfter(Instant.parse("2020-01-01T00:00:00Z"));
        assertThat(anchor.notificationSettings()).extracting(NotificationSettingDetail::event)
                .containsExactly(NotificationEvent.CA_CERTIFICATE_EXPIRY, NotificationEvent.END_ENTITY_CERTIFICATE_EXPIRY);
        assertThat(anchor.notificationSettings()).allSatisfy(setting -> assertThat(setting.threshold()).isEqualTo(45));
    }

    @Test
    @Order(2)
    @DisplayName("A trust anchor is read by its id, its ARN and its alias ARN, and lists with pagination")
    void getAndListTrustAnchor() {
        assertThat(client.getTrustAnchor(r -> r.trustAnchorId(anchor.trustAnchorId())).trustAnchor().name())
                .isEqualTo("sdk-test-anchor");
        assertThat(client.getTrustAnchor(r -> r.trustAnchorId(anchor.trustAnchorArn())).trustAnchor().trustAnchorId())
                .isEqualTo(anchor.trustAnchorId());
        assertThat(client.getTrustAnchor(r -> r.trustAnchorId(anchor.aliasArn())).trustAnchor().trustAnchorId())
                .isEqualTo(anchor.trustAnchorId());
        assertThat(client.listTrustAnchorsPaginator(r -> r.pageSize(1)).trustAnchors())
                .extracting(TrustAnchorDetail::trustAnchorId).contains(anchor.trustAnchorId());
    }

    @Test
    @Order(3)
    @DisplayName("Update, disable and enable change the trust anchor")
    void updateDisableEnableTrustAnchor() {
        TrustAnchorDetail updated = client.updateTrustAnchor(r -> r.trustAnchorId(anchor.trustAnchorId())
                .name("sdk-test-anchor-renamed").crlDpRevocationEnabled(true)).trustAnchor();
        assertThat(updated.name()).isEqualTo("sdk-test-anchor-renamed");
        assertThat(updated.crlDpRevocationEnabled()).isTrue();

        assertThat(client.disableTrustAnchor(r -> r.trustAnchorId(anchor.trustAnchorId())).trustAnchor().enabled())
                .isFalse();
        assertThat(client.enableTrustAnchor(r -> r.trustAnchorId(anchor.trustAnchorId())).trustAnchor().enabled())
                .isTrue();
    }

    @Test
    @Order(4)
    @DisplayName("Notification settings are put and reset")
    void notificationSettings() {
        TrustAnchorDetail changed = client.putNotificationSettings(r -> r.trustAnchorId(anchor.trustAnchorId())
                .notificationSettings(s -> s.event(NotificationEvent.CA_CERTIFICATE_EXPIRY).enabled(false)
                        .threshold(20))).trustAnchor();
        assertThat(changed.notificationSettings().get(0).enabled()).isFalse();
        assertThat(changed.notificationSettings().get(0).threshold()).isEqualTo(20);

        TrustAnchorDetail reset = client.resetNotificationSettings(r -> r.trustAnchorId(anchor.trustAnchorId())
                .notificationSettingKeys(k -> k.event(NotificationEvent.CA_CERTIFICATE_EXPIRY))).trustAnchor();
        assertThat(reset.notificationSettings().get(0).enabled()).isTrue();
        assertThat(reset.notificationSettings().get(0).threshold()).isEqualTo(45);
    }

    @Test
    @Order(5)
    @DisplayName("CreateProfile applies the default duration and attribute mappings")
    void createProfile() {
        String roleArn = "arn:aws:iam::" + ACCOUNT + ":role/sdk-test-workload";
        profile = client.createProfile(r -> r.name("sdk-test-profile").roleArns(roleArn)).profile();

        assertThat(profile.profileArn()).contains(":profile/" + profile.profileId());
        assertThat(profile.durationSeconds()).isEqualTo(3600);
        assertThat(profile.roleArns()).containsExactly(roleArn);
        assertThat(profile.createdAt()).isNotNull();
        assertThat(profile.attributeMappings()).extracting(AttributeMapping::certificateField)
                .containsExactly(CertificateField.X509_SUBJECT, CertificateField.X509_ISSUER, CertificateField.X509_SAN);
    }

    @Test
    @Order(6)
    @DisplayName("Attribute mappings are put and deleted per field")
    void attributeMappings() {
        ProfileDetail mapped = client.putAttributeMapping(r -> r.profileId(profile.profileArn())
                .certificateField(CertificateField.X509_SUBJECT)
                .mappingRules(MappingRule.builder().specifier("CN").build(),
                        MappingRule.builder().specifier("OU").build())).profile();
        assertThat(mapped.attributeMappings().get(0).mappingRules()).extracting(MappingRule::specifier)
                .containsExactly("CN", "OU");

        ProfileDetail narrowed = client.deleteAttributeMapping(r -> r.profileId(profile.profileId())
                .certificateField(CertificateField.X509_SUBJECT).specifiers("OU")).profile();
        assertThat(narrowed.attributeMappings().get(0).mappingRules()).extracting(MappingRule::specifier)
                .containsExactly("CN");

        ProfileDetail dropped = client.deleteAttributeMapping(r -> r.profileId(profile.profileId())
                .certificateField(CertificateField.X509_SAN)).profile();
        assertThat(dropped.attributeMappings()).hasSize(2);
    }

    @Test
    @Order(7)
    @DisplayName("ImportCrl stores the CRL against the trust anchor and echoes its data")
    void importCrl() {
        crl = client.importCrl(r -> r.name("sdk-test-crl").trustAnchorArn(anchor.trustAnchorArn())
                .crlData(SdkBytes.fromString(CRL_PEM, StandardCharsets.US_ASCII))).crl();

        assertThat(crl.crlArn()).contains(":crl/" + crl.crlId());
        assertThat(crl.trustAnchorArn()).isEqualTo(anchor.trustAnchorArn());
        assertThat(crl.crlData().asString(StandardCharsets.US_ASCII)).isEqualTo(CRL_PEM);
        assertThat(client.disableCrl(r -> r.crlId(crl.crlArn())).crl().enabled()).isFalse();
        assertThat(client.updateCrl(r -> r.crlId(crl.crlId()).name("sdk-test-crl-renamed")).crl().name())
                .isEqualTo("sdk-test-crl-renamed");
        assertThat(client.listCrlsPaginator(r -> r.pageSize(10)).crls()).extracting(CrlDetail::crlId)
                .contains(crl.crlId());
    }

    @Test
    @Order(8)
    @DisplayName("Tags are added, listed and removed")
    void tags() {
        client.tagResource(r -> r.resourceArn(profile.profileArn())
                .tags(Tag.builder().key("env").value("compat").build(), Tag.builder().key("tier").value("1").build()));
        List<Tag> tags = client.listTagsForResource(r -> r.resourceArn(profile.profileArn())).tags();
        assertThat(tags).extracting(Tag::key).containsExactly("env", "tier");

        client.untagResource(r -> r.resourceArn(profile.profileArn()).tagKeys("tier"));
        assertThat(client.listTagsForResource(r -> r.resourceArn(profile.profileArn())).tags())
                .extracting(Tag::key).containsExactly("env");
        assertThat(client.listTagsForResource(r -> r.resourceArn(anchor.trustAnchorArn())).tags())
                .extracting(Tag::value).containsExactly("compat");
    }

    @Test
    @Order(9)
    @DisplayName("Validation and not-found errors are typed SDK exceptions")
    void errors() {
        assertThatThrownBy(() -> client.createProfile(r -> r.name("sdk-test-bad").roleArns(
                "arn:aws:iam::" + ACCOUNT + ":role/x").durationSeconds(60)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("durationSeconds");
        assertThatThrownBy(() -> client.createTrustAnchor(r -> r.name("sdk-test-pca")
                .source(s -> s.sourceType(TrustAnchorType.AWS_ACM_PCA).sourceData(d -> d.acmPcaArn(
                        "arn:aws:acm-pca:us-east-1:" + ACCOUNT + ":certificate-authority/x")))))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> client.getProfile(r -> r.profileId("00000000-0000-0000-0000-000000000000")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @Order(10)
    @DisplayName("Deleting the trust anchor deletes its CRLs")
    void deleteTrustAnchor() {
        TrustAnchorDetail deleted = client.deleteTrustAnchor(r -> r.trustAnchorId(anchor.trustAnchorId()))
                .trustAnchor();
        assertThat(deleted.trustAnchorId()).isEqualTo(anchor.trustAnchorId());
        anchor = null;
        assertThatThrownBy(() -> client.getCrl(r -> r.crlId(crl.crlId())))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
