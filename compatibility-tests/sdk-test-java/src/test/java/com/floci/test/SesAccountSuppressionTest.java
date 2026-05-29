package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.Collections;
import java.util.List;

import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.GetAccountRequest;
import software.amazon.awssdk.services.sesv2.model.GetAccountResponse;
import software.amazon.awssdk.services.sesv2.model.PutAccountSuppressionAttributesRequest;
import software.amazon.awssdk.services.sesv2.model.SuppressionListReason;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SES V2 PutAccountSuppressionAttributes")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesAccountSuppressionTest {

    private static SesV2Client sesV2;
    private static List<SuppressionListReason> originalReasons;

    @BeforeAll
    static void setup() {
        sesV2 = TestFixtures.sesV2Client();
        // Capture the caller's existing account-level setting so cleanup can restore
        // it — the suite can run against real AWS and must not leave auto-suppression
        // disabled for the caller's account.
        originalReasons = sesV2.getAccount(GetAccountRequest.builder().build())
                .suppressionAttributes()
                .suppressedReasons();
    }

    @AfterAll
    static void cleanup() {
        if (sesV2 == null) {
            return;
        }
        try {
            // Let restore failures propagate — the test mutates account-level state,
            // so a silent failure here would leave a real AWS caller's suppression
            // setting disabled even though the suite reports green.
            sesV2.putAccountSuppressionAttributes(PutAccountSuppressionAttributesRequest.builder()
                    .suppressedReasons(originalReasons)
                    .build());
        } finally {
            sesV2.close();
        }
    }

    @Test
    @Order(1)
    void putAndGet_suppressedReasonsRoundTrip() {
        sesV2.putAccountSuppressionAttributes(PutAccountSuppressionAttributesRequest.builder()
                .suppressedReasons(SuppressionListReason.BOUNCE, SuppressionListReason.COMPLAINT)
                .build());

        GetAccountResponse account = sesV2.getAccount(GetAccountRequest.builder().build());
        assertThat(account.suppressionAttributes()).isNotNull();
        assertThat(account.suppressionAttributes().suppressedReasons())
                .containsExactlyInAnyOrder(SuppressionListReason.BOUNCE, SuppressionListReason.COMPLAINT);
    }

    @Test
    @Order(2)
    void put_explicitEmptyList_clearsReasons() {
        sesV2.putAccountSuppressionAttributes(PutAccountSuppressionAttributesRequest.builder()
                .suppressedReasons(Collections.emptyList())
                .build());

        GetAccountResponse account = sesV2.getAccount(GetAccountRequest.builder().build());
        assertThat(account.suppressionAttributes().suppressedReasons()).isEmpty();
    }
}
