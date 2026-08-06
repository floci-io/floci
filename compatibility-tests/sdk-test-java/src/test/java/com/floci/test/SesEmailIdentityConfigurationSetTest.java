package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.CreateConfigurationSetRequest;
import software.amazon.awssdk.services.sesv2.model.CreateEmailIdentityRequest;
import software.amazon.awssdk.services.sesv2.model.DeleteConfigurationSetRequest;
import software.amazon.awssdk.services.sesv2.model.DeleteEmailIdentityRequest;
import software.amazon.awssdk.services.sesv2.model.GetEmailIdentityRequest;
import software.amazon.awssdk.services.sesv2.model.NotFoundException;
import software.amazon.awssdk.services.sesv2.model.PutEmailIdentityConfigurationSetAttributesRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SDK compatibility test for the SES v2 {@code PutEmailIdentityConfigurationSetAttributes}
 * action using the AWS Java SDK v2 {@link SesV2Client}: associating a default configuration set
 * with an email identity, reading it back via {@code GetEmailIdentity}, clearing it, and the
 * modeled {@link NotFoundException} for an unknown identity.
 */
@DisplayName("SES V2 PutEmailIdentityConfigurationSetAttributes")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesEmailIdentityConfigurationSetTest {

    private static SesV2Client sesV2;
    private static String identity;
    private static String configSet;

    @BeforeAll
    static void setup() {
        sesV2 = TestFixtures.sesV2Client();
        String suffix = TestFixtures.uniqueName();
        identity = "sdk-cs-attr-" + suffix + "@example.com";
        configSet = "sdk-cs-attr-" + suffix;
        sesV2.createConfigurationSet(CreateConfigurationSetRequest.builder()
                .configurationSetName(configSet).build());
        sesV2.createEmailIdentity(CreateEmailIdentityRequest.builder()
                .emailIdentity(identity).build());
    }

    @AfterAll
    static void cleanup() {
        if (sesV2 != null) {
            try {
                sesV2.deleteEmailIdentity(DeleteEmailIdentityRequest.builder()
                        .emailIdentity(identity).build());
            } catch (Exception e) {
                System.err.println("[SesEmailIdentityConfigurationSetTest] cleanup warning – "
                        + "deleteEmailIdentity: " + e.getMessage());
            }
            try {
                sesV2.deleteConfigurationSet(DeleteConfigurationSetRequest.builder()
                        .configurationSetName(configSet).build());
            } catch (Exception e) {
                System.err.println("[SesEmailIdentityConfigurationSetTest] cleanup warning – "
                        + "deleteConfigurationSet: " + e.getMessage());
            }
            sesV2.close();
        }
    }

    @Test
    @Order(1)
    void associate_readsBackViaGetEmailIdentity() {
        sesV2.putEmailIdentityConfigurationSetAttributes(
                PutEmailIdentityConfigurationSetAttributesRequest.builder()
                        .emailIdentity(identity)
                        .configurationSetName(configSet)
                        .build());

        assertThat(sesV2.getEmailIdentity(GetEmailIdentityRequest.builder()
                .emailIdentity(identity).build()).configurationSetName())
                .isEqualTo(configSet);
    }

    @Test
    @Order(2)
    void emptyRequest_clearsAssociation() {
        sesV2.putEmailIdentityConfigurationSetAttributes(
                PutEmailIdentityConfigurationSetAttributesRequest.builder()
                        .emailIdentity(identity)
                        .build());

        assertThat(sesV2.getEmailIdentity(GetEmailIdentityRequest.builder()
                .emailIdentity(identity).build()).configurationSetName())
                .isNull();
    }

    @Test
    @Order(3)
    void unknownIdentity_raisesNotFound() {
        assertThatThrownBy(() -> sesV2.putEmailIdentityConfigurationSetAttributes(
                PutEmailIdentityConfigurationSetAttributesRequest.builder()
                        .emailIdentity("sdk-cs-attr-ghost-" + System.currentTimeMillis() + "@example.com")
                        .configurationSetName(configSet)
                        .build()))
                .isInstanceOf(NotFoundException.class);
    }
}
