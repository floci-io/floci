package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.ConfigurationSet;
import software.amazon.awssdk.services.ses.model.CreateConfigurationSetRequest;
import software.amazon.awssdk.services.ses.model.DeleteConfigurationSetRequest;
import software.amazon.awssdk.services.ses.model.DescribeConfigurationSetRequest;
import software.amazon.awssdk.services.ses.model.DescribeConfigurationSetResponse;
import software.amazon.awssdk.services.ses.model.ListConfigurationSetsRequest;
import software.amazon.awssdk.services.ses.model.ListConfigurationSetsResponse;

import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.GetConfigurationSetRequest;
import software.amazon.awssdk.services.sesv2.model.GetConfigurationSetResponse;
import software.amazon.awssdk.services.sesv2.model.PutConfigurationSetSuppressionOptionsRequest;
import software.amazon.awssdk.services.sesv2.model.SuppressionListReason;
import software.amazon.awssdk.services.sesv2.model.Tag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SES ConfigurationSet compatibility tests against the V1 (query) SES API
 * and the V2 (REST JSON) SESv2 API.
 */
@DisplayName("SES Configuration Sets")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesConfigurationSetTest {

    private static SesClient sesV1;
    private static SesV2Client sesV2;
    private static String v1Name;
    private static String v2Name;
    private static String suppressionCsName;

    @BeforeAll
    static void setup() {
        sesV1 = TestFixtures.sesClient();
        sesV2 = TestFixtures.sesV2Client();
        String suffix = TestFixtures.uniqueName();
        v1Name = "sdk-v1-cs-" + suffix;
        v2Name = "sdk-v2-cs-" + suffix;
        suppressionCsName = "sdk-v2-cs-supp-" + suffix;
    }

    @AfterAll
    static void cleanup() {
        if (sesV1 != null) {
            safelyDeleteV1(v1Name);
            sesV1.close();
        }
        if (sesV2 != null) {
            safelyDeleteV2(v2Name);
            safelyDeleteV2(suppressionCsName);
            sesV2.close();
        }
    }

    // ─────────────────────────── V2 (SESv2) ───────────────────────────

    @Test
    @Order(1)
    void v2CreateAndGetConfigurationSet() {
        sesV2.createConfigurationSet(software.amazon.awssdk.services.sesv2.model.CreateConfigurationSetRequest.builder()
                .configurationSetName(v2Name)
                .tags(Tag.builder().key("env").value("test").build())
                .build());

        GetConfigurationSetResponse response = sesV2.getConfigurationSet(GetConfigurationSetRequest.builder()
                .configurationSetName(v2Name)
                .build());
        assertThat(response.configurationSetName()).isEqualTo(v2Name);
        assertThat(response.tags()).anyMatch(t -> "env".equals(t.key()) && "test".equals(t.value()));
    }

    @Test
    @Order(2)
    void v2CreateDuplicateRejectedWith400() {
        assertThatThrownBy(() -> sesV2.createConfigurationSet(software.amazon.awssdk.services.sesv2.model.CreateConfigurationSetRequest.builder()
                .configurationSetName(v2Name)
                .build()))
                .isInstanceOf(AwsServiceException.class)
                .extracting(e -> ((AwsServiceException) e).statusCode())
                .isEqualTo(400);
    }

    @Test
    @Order(3)
    void v2GetUnknownReturns404() {
        assertThatThrownBy(() -> sesV2.getConfigurationSet(GetConfigurationSetRequest.builder()
                .configurationSetName("sdk-v2-cs-missing-" + System.currentTimeMillis())
                .build()))
                .isInstanceOf(AwsServiceException.class)
                .extracting(e -> ((AwsServiceException) e).statusCode())
                .isEqualTo(404);
    }

    @Test
    @Order(4)
    void v2ListConfigurationSetsIncludesCreated() {
        software.amazon.awssdk.services.sesv2.model.ListConfigurationSetsResponse response =
                sesV2.listConfigurationSets(software.amazon.awssdk.services.sesv2.model.ListConfigurationSetsRequest.builder().build());
        assertThat(response.configurationSets()).contains(v2Name);
    }

    @Test
    @Order(5)
    void v2DeleteConfigurationSet() {
        sesV2.deleteConfigurationSet(software.amazon.awssdk.services.sesv2.model.DeleteConfigurationSetRequest.builder()
                .configurationSetName(v2Name)
                .build());

        assertThatThrownBy(() -> sesV2.getConfigurationSet(GetConfigurationSetRequest.builder()
                .configurationSetName(v2Name)
                .build()))
                .isInstanceOf(AwsServiceException.class)
                .extracting(e -> ((AwsServiceException) e).statusCode())
                .isEqualTo(404);
    }

    // ─────────────────────────── V1 (SES) ───────────────────────────

    @Test
    @Order(10)
    void v1CreateAndDescribeConfigurationSet() {
        sesV1.createConfigurationSet(CreateConfigurationSetRequest.builder()
                .configurationSet(ConfigurationSet.builder().name(v1Name).build())
                .build());

        DescribeConfigurationSetResponse response = sesV1.describeConfigurationSet(
                DescribeConfigurationSetRequest.builder()
                        .configurationSetName(v1Name)
                        .build());
        assertThat(response.configurationSet().name()).isEqualTo(v1Name);
    }

    @Test
    @Order(11)
    void v1CreateDuplicateRaises() {
        assertThatThrownBy(() -> sesV1.createConfigurationSet(CreateConfigurationSetRequest.builder()
                .configurationSet(ConfigurationSet.builder().name(v1Name).build())
                .build()))
                .isInstanceOf(AwsServiceException.class)
                .extracting(e -> ((AwsServiceException) e).statusCode())
                .isEqualTo(400);
    }

    @Test
    @Order(12)
    void v1DescribeUnknownRaises() {
        assertThatThrownBy(() -> sesV1.describeConfigurationSet(DescribeConfigurationSetRequest.builder()
                .configurationSetName("sdk-v1-cs-missing-" + System.currentTimeMillis())
                .build()))
                .isInstanceOf(AwsServiceException.class)
                .extracting(e -> ((AwsServiceException) e).statusCode())
                .isEqualTo(400);
    }

    @Test
    @Order(13)
    void v1ListConfigurationSetsIncludesCreated() {
        ListConfigurationSetsResponse response = sesV1.listConfigurationSets(
                ListConfigurationSetsRequest.builder().build());
        assertThat(response.configurationSets())
                .anyMatch(cs -> v1Name.equals(cs.name()));
    }

    @Test
    @Order(14)
    void v1DeleteConfigurationSet() {
        sesV1.deleteConfigurationSet(DeleteConfigurationSetRequest.builder()
                .configurationSetName(v1Name)
                .build());

        assertThatThrownBy(() -> sesV1.describeConfigurationSet(DescribeConfigurationSetRequest.builder()
                .configurationSetName(v1Name)
                .build()))
                .isInstanceOf(AwsServiceException.class)
                .extracting(e -> ((AwsServiceException) e).statusCode())
                .isEqualTo(400);
    }

    // ────────────────────────── Validation ──────────────────────────

    @Test
    @Order(20)
    void v2CreateRejectsInvalidName() {
        assertThatThrownBy(() -> sesV2.createConfigurationSet(software.amazon.awssdk.services.sesv2.model.CreateConfigurationSetRequest.builder()
                .configurationSetName("invalid name!")
                .build()))
                .isInstanceOf(AwsServiceException.class)
                .extracting(e -> ((AwsServiceException) e).statusCode())
                .isEqualTo(400);
    }

    @Test
    @Order(21)
    void v1CreateRejectsInvalidName() {
        assertThatThrownBy(() -> sesV1.createConfigurationSet(CreateConfigurationSetRequest.builder()
                .configurationSet(ConfigurationSet.builder().name("invalid name!").build())
                .build()))
                .isInstanceOf(AwsServiceException.class)
                .extracting(e -> ((AwsServiceException) e).statusCode())
                .isEqualTo(400);
    }

    // ─────────────────── V2 SuppressionOptions (per-CS) ───────────────────

    @Test
    @Order(30)
    void v2SuppressionOptions_setup_createsSuppressionConfigurationSet() {
        sesV2.createConfigurationSet(software.amazon.awssdk.services.sesv2.model.CreateConfigurationSetRequest.builder()
                .configurationSetName(suppressionCsName)
                .build());

        // Before any PutSuppressionOptions, the response has no SuppressionOptions block.
        GetConfigurationSetResponse before = sesV2.getConfigurationSet(GetConfigurationSetRequest.builder()
                .configurationSetName(suppressionCsName)
                .build());
        assertThat(before.suppressionOptions()).isNull();
    }

    @Test
    @Order(31)
    void v2PutSuppressionOptions_bounceOnly_visibleOnGet() {
        sesV2.putConfigurationSetSuppressionOptions(PutConfigurationSetSuppressionOptionsRequest.builder()
                .configurationSetName(suppressionCsName)
                .suppressedReasons(SuppressionListReason.BOUNCE)
                .build());

        GetConfigurationSetResponse after = sesV2.getConfigurationSet(GetConfigurationSetRequest.builder()
                .configurationSetName(suppressionCsName)
                .build());
        assertThat(after.suppressionOptions()).isNotNull();
        assertThat(after.suppressionOptions().suppressedReasons())
                .containsExactly(SuppressionListReason.BOUNCE);
    }

    @Test
    @Order(32)
    void v2PutSuppressionOptions_bothReasons() {
        sesV2.putConfigurationSetSuppressionOptions(PutConfigurationSetSuppressionOptionsRequest.builder()
                .configurationSetName(suppressionCsName)
                .suppressedReasons(SuppressionListReason.BOUNCE, SuppressionListReason.COMPLAINT)
                .build());

        GetConfigurationSetResponse after = sesV2.getConfigurationSet(GetConfigurationSetRequest.builder()
                .configurationSetName(suppressionCsName)
                .build());
        assertThat(after.suppressionOptions().suppressedReasons())
                .containsExactlyInAnyOrder(SuppressionListReason.BOUNCE, SuppressionListReason.COMPLAINT);
    }

    @Test
    @Order(33)
    void v2PutSuppressionOptions_emptyList_explicitlyDisablesFiltering() {
        // Per the AWS V2 contract, an empty SuppressedReasons list is the documented way
        // to disable suppression filtering for a configuration set.
        sesV2.putConfigurationSetSuppressionOptions(PutConfigurationSetSuppressionOptionsRequest.builder()
                .configurationSetName(suppressionCsName)
                .suppressedReasons(java.util.Collections.emptyList())
                .build());

        GetConfigurationSetResponse after = sesV2.getConfigurationSet(GetConfigurationSetRequest.builder()
                .configurationSetName(suppressionCsName)
                .build());
        assertThat(after.suppressionOptions()).isNotNull();
        assertThat(after.suppressionOptions().suppressedReasons()).isEmpty();
    }

    @Test
    @Order(34)
    void v2PutSuppressionOptions_invalidReason_rejectedAt400() {
        // The typed enum (SuppressionListReason) prevents passing an unknown value through
        // the SDK, so use the WithStrings variant to round-trip the literal "INVALID" to
        // the wire and exercise server-side validation.
        assertThatThrownBy(() -> sesV2.putConfigurationSetSuppressionOptions(
                PutConfigurationSetSuppressionOptionsRequest.builder()
                        .configurationSetName(suppressionCsName)
                        .suppressedReasonsWithStrings("BOUNCE", "INVALID")
                        .build()))
                .isInstanceOf(AwsServiceException.class)
                .extracting(e -> ((AwsServiceException) e).statusCode())
                .isEqualTo(400);
    }

    @Test
    @Order(35)
    void v2PutSuppressionOptions_unknownConfigurationSet_rejectedAt404() {
        assertThatThrownBy(() -> sesV2.putConfigurationSetSuppressionOptions(
                PutConfigurationSetSuppressionOptionsRequest.builder()
                        .configurationSetName(suppressionCsName + "-does-not-exist")
                        .suppressedReasons(SuppressionListReason.BOUNCE)
                        .build()))
                .isInstanceOf(AwsServiceException.class)
                .extracting(e -> ((AwsServiceException) e).statusCode())
                .isEqualTo(404);
    }

    // ─────────────────────────── Helpers ───────────────────────────

    private static void safelyDeleteV1(String name) {
        try {
            sesV1.deleteConfigurationSet(DeleteConfigurationSetRequest.builder()
                    .configurationSetName(name)
                    .build());
        } catch (Exception ignored) {}
    }

    private static void safelyDeleteV2(String name) {
        try {
            sesV2.deleteConfigurationSet(software.amazon.awssdk.services.sesv2.model.DeleteConfigurationSetRequest.builder()
                    .configurationSetName(name)
                    .build());
        } catch (Exception ignored) {}
    }
}
