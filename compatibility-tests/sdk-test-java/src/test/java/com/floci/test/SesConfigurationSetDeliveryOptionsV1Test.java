package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.ConfigurationSet;
import software.amazon.awssdk.services.ses.model.ConfigurationSetDoesNotExistException;
import software.amazon.awssdk.services.ses.model.CreateConfigurationSetRequest;
import software.amazon.awssdk.services.ses.model.DeleteConfigurationSetRequest;
import software.amazon.awssdk.services.ses.model.DeliveryOptions;
import software.amazon.awssdk.services.ses.model.PutConfigurationSetDeliveryOptionsRequest;

import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.GetConfigurationSetRequest;
import software.amazon.awssdk.services.sesv2.model.GetConfigurationSetResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SDK compatibility test for the SES V1 (Query) {@code PutConfigurationSetDeliveryOptions}
 * action. The V1 API names the TLS policy {@code Require}/{@code Optional} (PascalCase),
 * while Floci stores the V2 canonical {@code REQUIRE}/{@code OPTIONAL}. A value written
 * through the V1 {@link SesClient} must therefore read back as {@code REQUIRE} through the
 * V2 {@code GetConfigurationSet} response — exercising both the action wiring and the
 * PascalCase normalization end to end.
 */
@DisplayName("SES V1 PutConfigurationSetDeliveryOptions")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesConfigurationSetDeliveryOptionsV1Test {

    private static SesClient sesV1;
    private static SesV2Client sesV2;
    private static String csName;

    @BeforeAll
    static void setup() {
        sesV1 = TestFixtures.sesClient();
        sesV2 = TestFixtures.sesV2Client();
        csName = "sdk-v1-cs-delivery-" + TestFixtures.uniqueName();
        sesV1.createConfigurationSet(CreateConfigurationSetRequest.builder()
                .configurationSet(ConfigurationSet.builder().name(csName).build())
                .build());
    }

    @AfterAll
    static void cleanup() {
        if (sesV1 != null) {
            try {
                sesV1.deleteConfigurationSet(DeleteConfigurationSetRequest.builder()
                        .configurationSetName(csName).build());
            } catch (Exception ignored) {}
            sesV1.close();
        }
        if (sesV2 != null) {
            sesV2.close();
        }
    }

    @Test
    @Order(1)
    void v1PutDeliveryOptions_pascalCaseTlsPolicy_readsBackAsCanonical() {
        sesV1.putConfigurationSetDeliveryOptions(PutConfigurationSetDeliveryOptionsRequest.builder()
                .configurationSetName(csName)
                .deliveryOptions(DeliveryOptions.builder().tlsPolicy("Require").build())
                .build());

        GetConfigurationSetResponse response = sesV2.getConfigurationSet(
                GetConfigurationSetRequest.builder().configurationSetName(csName).build());
        assertThat(response.deliveryOptions()).isNotNull();
        assertThat(response.deliveryOptions().tlsPolicyAsString()).isEqualTo("REQUIRE");
    }

    @Test
    @Order(2)
    void v1PutDeliveryOptions_emptyPayload_clearsTheBlock() {
        sesV1.putConfigurationSetDeliveryOptions(PutConfigurationSetDeliveryOptionsRequest.builder()
                .configurationSetName(csName)
                .deliveryOptions(DeliveryOptions.builder().tlsPolicy("Require").build())
                .build());

        // A put with no DeliveryOptions fields clears the block rather than persisting an
        // empty object, matching the V2 PutConfigurationSetDeliveryOptions behavior.
        sesV1.putConfigurationSetDeliveryOptions(PutConfigurationSetDeliveryOptionsRequest.builder()
                .configurationSetName(csName)
                .build());

        GetConfigurationSetResponse response = sesV2.getConfigurationSet(
                GetConfigurationSetRequest.builder().configurationSetName(csName).build());
        assertThat(response.deliveryOptions()).isNull();
    }

    @Test
    @Order(3)
    void v1PutDeliveryOptions_unknownConfigurationSet_raisesModeledException() {
        assertThatThrownBy(() -> sesV1.putConfigurationSetDeliveryOptions(
                PutConfigurationSetDeliveryOptionsRequest.builder()
                        .configurationSetName("sdk-v1-cs-delivery-missing-" + System.currentTimeMillis())
                        .deliveryOptions(DeliveryOptions.builder().tlsPolicy("Require").build())
                        .build()))
                .isInstanceOf(ConfigurationSetDoesNotExistException.class);
    }
}
