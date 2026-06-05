package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.ConfigurationSetAttribute;
import software.amazon.awssdk.services.ses.model.DescribeConfigurationSetRequest;
import software.amazon.awssdk.services.ses.model.DescribeConfigurationSetResponse;
import software.amazon.awssdk.services.ses.model.UpdateConfigurationSetSendingEnabledRequest;

import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.CreateConfigurationSetRequest;
import software.amazon.awssdk.services.sesv2.model.DeleteConfigurationSetRequest;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.GetConfigurationSetRequest;
import software.amazon.awssdk.services.sesv2.model.GetConfigurationSetResponse;
import software.amazon.awssdk.services.sesv2.model.Message;
import software.amazon.awssdk.services.sesv2.model.NotFoundException;
import software.amazon.awssdk.services.sesv2.model.PutConfigurationSetSendingOptionsRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse;
import software.amazon.awssdk.services.sesv2.model.SendingPausedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SDK compatibility test for the per-configuration-set sending toggle. Verifies
 * the AWS Java SDK v2 marshalling of:
 *   - {@code sesv2.putConfigurationSetSendingOptions(SendingEnabled=...)}
 *   - {@code sesv2.getConfigurationSet(...)} response carrying the
 *     {@code SendingOptions.SendingEnabled} block
 *   - {@code ses.updateConfigurationSetSendingEnabled(...)}  (v1 Query)
 *   - {@code ses.describeConfigurationSet(...)} with the
 *     {@code reputationOptions} attribute returning
 *     {@code ReputationOptions.SendingEnabled}
 *   - Cross-API state sharing (v1 and v2 read/write the same flag)
 *   - {@code sesv2.sendEmail(...)} rejected with {@link SendingPausedException}
 *     when the configured set is disabled
 */
@DisplayName("SES Per-Configuration-Set Sending Toggle (v1 + v2)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesConfigurationSetSendingTest {

    private static final String CS_NAME = "compat-cs-sending";
    private static final String FROM = "sender@example.com";
    private static final String TO = "recipient@example.com";

    private static SesClient sesV1;
    private static SesV2Client sesV2;

    @BeforeAll
    static void setup() {
        sesV1 = TestFixtures.sesClient();
        sesV2 = TestFixtures.sesV2Client();
        // Start clean — drop any leftover CS from a previous run.
        try {
            sesV2.deleteConfigurationSet(DeleteConfigurationSetRequest.builder()
                    .configurationSetName(CS_NAME).build());
        } catch (Exception ignored) {}
        sesV2.createConfigurationSet(CreateConfigurationSetRequest.builder()
                .configurationSetName(CS_NAME).build());
    }

    @AfterAll
    static void cleanup() {
        if (sesV2 != null) {
            try {
                sesV2.deleteConfigurationSet(DeleteConfigurationSetRequest.builder()
                        .configurationSetName(CS_NAME).build());
            } catch (Exception ignored) {}
            sesV2.close();
        }
        if (sesV1 != null) {
            sesV1.close();
        }
    }

    @Test
    @Order(1)
    void v2GetConfigurationSet_defaultsToSendingEnabledTrue() {
        GetConfigurationSetResponse response = sesV2.getConfigurationSet(
                GetConfigurationSetRequest.builder().configurationSetName(CS_NAME).build());

        assertThat(response.sendingOptions()).isNotNull();
        assertThat(response.sendingOptions().sendingEnabled()).isTrue();
    }

    @Test
    @Order(2)
    void v2SendEmail_defaultEnabled_succeeds() {
        SendEmailResponse response = sesV2.sendEmail(buildSimpleSend(CS_NAME));
        assertThat(response.messageId()).isNotBlank();
    }

    @Test
    @Order(3)
    void v2PutConfigurationSetSendingOptions_disables() {
        sesV2.putConfigurationSetSendingOptions(PutConfigurationSetSendingOptionsRequest.builder()
                .configurationSetName(CS_NAME)
                .sendingEnabled(false)
                .build());

        GetConfigurationSetResponse response = sesV2.getConfigurationSet(
                GetConfigurationSetRequest.builder().configurationSetName(CS_NAME).build());
        assertThat(response.sendingOptions().sendingEnabled()).isFalse();
    }

    @Test
    @Order(4)
    void v1DescribeConfigurationSet_reputationOptions_reflectsDisabled() {
        DescribeConfigurationSetResponse response = sesV1.describeConfigurationSet(
                DescribeConfigurationSetRequest.builder()
                        .configurationSetName(CS_NAME)
                        .configurationSetAttributeNames(ConfigurationSetAttribute.REPUTATION_OPTIONS)
                        .build());

        assertThat(response.reputationOptions()).isNotNull();
        assertThat(response.reputationOptions().sendingEnabled()).isFalse();
    }

    @Test
    @Order(5)
    void v2SendEmail_whenDisabled_isRejectedWithSendingPausedException() {
        assertThatThrownBy(() -> sesV2.sendEmail(buildSimpleSend(CS_NAME)))
                .isInstanceOf(SendingPausedException.class);
    }

    @Test
    @Order(6)
    void v1UpdateConfigurationSetSendingEnabled_reEnables() {
        sesV1.updateConfigurationSetSendingEnabled(
                UpdateConfigurationSetSendingEnabledRequest.builder()
                        .configurationSetName(CS_NAME)
                        .enabled(true)
                        .build());

        // Visible via v2 GetConfigurationSet
        GetConfigurationSetResponse v2Response = sesV2.getConfigurationSet(
                GetConfigurationSetRequest.builder().configurationSetName(CS_NAME).build());
        assertThat(v2Response.sendingOptions().sendingEnabled()).isTrue();

        // Visible via v1 DescribeConfigurationSet
        DescribeConfigurationSetResponse v1Response = sesV1.describeConfigurationSet(
                DescribeConfigurationSetRequest.builder()
                        .configurationSetName(CS_NAME)
                        .configurationSetAttributeNames(ConfigurationSetAttribute.REPUTATION_OPTIONS)
                        .build());
        assertThat(v1Response.reputationOptions().sendingEnabled()).isTrue();
    }

    @Test
    @Order(7)
    void v2SendEmail_afterReEnable_succeeds() {
        SendEmailResponse response = sesV2.sendEmail(buildSimpleSend(CS_NAME));
        assertThat(response.messageId()).isNotBlank();
    }

    @Test
    @Order(8)
    void v1DisableAndV2GetConfigurationSet_share_state() {
        sesV1.updateConfigurationSetSendingEnabled(
                UpdateConfigurationSetSendingEnabledRequest.builder()
                        .configurationSetName(CS_NAME)
                        .enabled(false)
                        .build());

        GetConfigurationSetResponse response = sesV2.getConfigurationSet(
                GetConfigurationSetRequest.builder().configurationSetName(CS_NAME).build());
        assertThat(response.sendingOptions().sendingEnabled()).isFalse();

        // Restore for the unknown-CS test below.
        sesV2.putConfigurationSetSendingOptions(PutConfigurationSetSendingOptionsRequest.builder()
                .configurationSetName(CS_NAME)
                .sendingEnabled(true)
                .build());
    }

    @Test
    @Order(9)
    void v2PutConfigurationSetSendingOptions_unknownCS_throwsNotFound() {
        assertThatThrownBy(() -> sesV2.putConfigurationSetSendingOptions(
                PutConfigurationSetSendingOptionsRequest.builder()
                        .configurationSetName("does-not-exist-cs")
                        .sendingEnabled(false)
                        .build()))
                .isInstanceOf(NotFoundException.class);
    }

    private static SendEmailRequest buildSimpleSend(String configSet) {
        return SendEmailRequest.builder()
                .fromEmailAddress(FROM)
                .destination(Destination.builder().toAddresses(TO).build())
                .configurationSetName(configSet)
                .content(EmailContent.builder()
                        .simple(Message.builder()
                                .subject(Content.builder().data("compat-send-toggle").build())
                                .body(Body.builder()
                                        .text(Content.builder().data("hi").build())
                                        .build())
                                .build())
                        .build())
                .build();
    }
}
