package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.parallel.ResourceLock;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.AccountSharedPoolDeliveryMode;
import software.amazon.awssdk.services.sesv2.model.BadRequestException;
import software.amazon.awssdk.services.sesv2.model.CreateConfigurationSetRequest;
import software.amazon.awssdk.services.sesv2.model.DeleteConfigurationSetRequest;
import software.amazon.awssdk.services.sesv2.model.DeliveryOptions;
import software.amazon.awssdk.services.sesv2.model.GetAccountRequest;
import software.amazon.awssdk.services.sesv2.model.GetConfigurationSetRequest;
import software.amazon.awssdk.services.sesv2.model.PutAccountDeliveryAttributesRequest;
import software.amazon.awssdk.services.sesv2.model.PutConfigurationSetDeliveryOptionsRequest;
import software.amazon.awssdk.services.sesv2.model.SharedPoolDeliveryMode;
import software.amazon.awssdk.services.sesv2.model.TlsPolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SES v2 global shared delivery (SharedPoolDeliveryMode)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@ResourceLock("ses-account")
class SesSharedPoolDeliveryTest {

    private static SesV2Client sesV2;
    private static SesClient sesV1;
    private static AccountSharedPoolDeliveryMode originalMode;
    private static String csName;

    @BeforeAll
    static void setup() {
        sesV2 = TestFixtures.sesV2Client();
        sesV1 = TestFixtures.sesClient();
        // The suite can run against real AWS: remember the account's mode so cleanup restores it.
        originalMode = sesV2.getAccount(GetAccountRequest.builder().build())
                .deliveryAttributes().sharedPoolDeliveryMode();
        csName = "sdk-cs-shared-pool-" + TestFixtures.uniqueName();
    }

    @AfterAll
    static void cleanup() {
        if (sesV2 != null) {
            try {
                sesV2.deleteConfigurationSet(DeleteConfigurationSetRequest.builder()
                        .configurationSetName(csName).build());
            } catch (Exception ignored) {
                // The set may never have been created when an earlier step failed.
            }
            if (originalMode != null) {
                sesV2.putAccountDeliveryAttributes(PutAccountDeliveryAttributesRequest.builder()
                        .sharedPoolDeliveryMode(originalMode).build());
            }
            sesV2.close();
        }
        if (sesV1 != null) {
            sesV1.close();
        }
    }

    @Test
    @Order(1)
    @DisplayName("GetAccount reports DeliveryAttributes before it is ever set")
    void getAccountReportsDeliveryAttributes() {
        assertThat(originalMode).isIn(AccountSharedPoolDeliveryMode.GLOBAL, AccountSharedPoolDeliveryMode.REGIONAL);
    }

    @Test
    @Order(2)
    @DisplayName("PutAccountDeliveryAttributes round-trips through GetAccount")
    void putAccountDeliveryAttributesRoundTrips() {
        for (AccountSharedPoolDeliveryMode mode : new AccountSharedPoolDeliveryMode[] {
                AccountSharedPoolDeliveryMode.REGIONAL, AccountSharedPoolDeliveryMode.GLOBAL}) {
            sesV2.putAccountDeliveryAttributes(PutAccountDeliveryAttributesRequest.builder()
                    .sharedPoolDeliveryMode(mode).build());
            assertThat(sesV2.getAccount(GetAccountRequest.builder().build())
                    .deliveryAttributes().sharedPoolDeliveryMode()).isEqualTo(mode);
        }
    }

    @Test
    @Order(3)
    @DisplayName("PutAccountDeliveryAttributes refuses the configuration-set-only ACCOUNT value")
    void putAccountDeliveryAttributesRefusesAccountValue() {
        assertThatThrownBy(() -> sesV2.putAccountDeliveryAttributes(PutAccountDeliveryAttributesRequest.builder()
                .sharedPoolDeliveryMode("ACCOUNT").build()))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @Order(4)
    @DisplayName("CreateConfigurationSet stores DeliveryOptions.SharedPoolDeliveryMode")
    void createConfigurationSetStoresMode() {
        sesV2.createConfigurationSet(CreateConfigurationSetRequest.builder()
                .configurationSetName(csName)
                .deliveryOptions(DeliveryOptions.builder()
                        .sharedPoolDeliveryMode(SharedPoolDeliveryMode.ACCOUNT).build())
                .build());

        assertThat(sesV2.getConfigurationSet(GetConfigurationSetRequest.builder()
                .configurationSetName(csName).build())
                .deliveryOptions().sharedPoolDeliveryMode()).isEqualTo(SharedPoolDeliveryMode.ACCOUNT);
    }

    @Test
    @Order(5)
    @DisplayName("PutConfigurationSetDeliveryOptions keeps the mode when the request omits it")
    void putDeliveryOptionsKeepsOmittedMode() {
        sesV2.putConfigurationSetDeliveryOptions(PutConfigurationSetDeliveryOptionsRequest.builder()
                .configurationSetName(csName)
                .sharedPoolDeliveryMode(SharedPoolDeliveryMode.REGIONAL)
                .build());
        sesV2.putConfigurationSetDeliveryOptions(PutConfigurationSetDeliveryOptionsRequest.builder()
                .configurationSetName(csName)
                .tlsPolicy(TlsPolicy.REQUIRE)
                .build());

        DeliveryOptions stored = sesV2.getConfigurationSet(GetConfigurationSetRequest.builder()
                .configurationSetName(csName).build()).deliveryOptions();
        assertThat(stored.tlsPolicy()).isEqualTo(TlsPolicy.REQUIRE);
        assertThat(stored.sharedPoolDeliveryMode()).isEqualTo(SharedPoolDeliveryMode.REGIONAL);
    }

    @Test
    @Order(6)
    @DisplayName("A v1 PutConfigurationSetDeliveryOptions keeps the mode")
    void v1PutDeliveryOptionsKeepsMode() {
        // The v1 request and DeliveryOptions types share their names with the imported v2 ones.
        sesV1.putConfigurationSetDeliveryOptions(
                software.amazon.awssdk.services.ses.model.PutConfigurationSetDeliveryOptionsRequest.builder()
                        .configurationSetName(csName)
                        .deliveryOptions(software.amazon.awssdk.services.ses.model.DeliveryOptions.builder()
                                .tlsPolicy("Optional").build())
                        .build());

        DeliveryOptions stored = sesV2.getConfigurationSet(GetConfigurationSetRequest.builder()
                .configurationSetName(csName).build()).deliveryOptions();
        assertThat(stored.tlsPolicy()).isEqualTo(TlsPolicy.OPTIONAL);
        assertThat(stored.sharedPoolDeliveryMode()).isEqualTo(SharedPoolDeliveryMode.REGIONAL);
    }
}
