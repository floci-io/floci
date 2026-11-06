package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.DisableOutboundWebIdentityFederationRequest;
import software.amazon.awssdk.services.iam.model.EnableOutboundWebIdentityFederationRequest;
import software.amazon.awssdk.services.iam.model.FeatureDisabledException;
import software.amazon.awssdk.services.iam.model.FeatureEnabledException;
import software.amazon.awssdk.services.iam.model.GetAccountPropertiesRequest;
import software.amazon.awssdk.services.iam.model.GetAccountPropertiesResponse;
import software.amazon.awssdk.services.iam.model.GetOutboundWebIdentityFederationInfoRequest;
import software.amazon.awssdk.services.iam.model.GetOutboundWebIdentityFederationInfoResponse;
import software.amazon.awssdk.services.iam.model.GlobalEndpointTokenVersion;
import software.amazon.awssdk.services.iam.model.PutAccountPropertiesRequest;
import software.amazon.awssdk.services.iam.model.SetSecurityTokenServicePreferencesRequest;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The account property and outbound federation operations driven through the AWS SDK rather than
 * hand-written requests.
 *
 * <p>This is the check the handcrafted XML assertions cannot make: the SDK parses the response
 * against its own model, so a mis-named element or a map serialised in the wrong shape fails here
 * even though the raw XML looked right. The {@code Properties} map is the interesting one, since it
 * travels as {@code Properties.entry.N.key} and {@code Properties.entry.N.value} in both
 * directions.
 *
 * <p>It also pins the error codes: the SDK maps {@code FeatureEnabled} and {@code FeatureDisabled}
 * onto typed exceptions, so emitting the wrong code would arrive as a plain {@code IamException}.
 */
@DisplayName("IAM Account Properties and Outbound Federation")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IamAccountPropertiesTest {

    private static final String NAMESPACE = "SdkTestNs";

    private static IamClient iam() {
        return TestFixtures.iamClient();
    }

    @Test
    @Order(1)
    @DisplayName("PutAccountProperties and GetAccountProperties round-trip the map")
    void propertiesRoundTrip() {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put(NAMESPACE + "/Enabled", "true");
        properties.put(NAMESPACE + "/Mode", "strict");

        iam().putAccountProperties(PutAccountPropertiesRequest.builder()
                .properties(properties)
                .build());

        GetAccountPropertiesResponse response = iam().getAccountProperties(
                GetAccountPropertiesRequest.builder().build());

        assertThat(response.hasProperties()).isTrue();
        assertThat(response.properties())
                .containsEntry(NAMESPACE + "/Enabled", "true")
                .containsEntry(NAMESPACE + "/Mode", "strict");
    }

    @Test
    @Order(2)
    @DisplayName("All keys in one request must share a namespace")
    void mixedNamespacesAreRejected() {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put(NAMESPACE + "/Alpha", "1");
        properties.put("OtherSdkNs/Beta", "2");

        assertThatThrownBy(() -> iam().putAccountProperties(PutAccountPropertiesRequest.builder()
                .properties(properties)
                .build()))
                .hasMessageContaining("same namespace");
    }

    @Test
    @Order(3)
    @DisplayName("SetSecurityTokenServicePreferences takes the version as a typed enum")
    void setTokenVersion() {
        iam().setSecurityTokenServicePreferences(
                SetSecurityTokenServicePreferencesRequest.builder()
                        .globalEndpointTokenVersion(GlobalEndpointTokenVersion.V2_TOKEN)
                        .build());

        // No getter for it in the API, so the assertion is that the SDK round-tripped the enum
        // without the service rejecting it.
        iam().setSecurityTokenServicePreferences(
                SetSecurityTokenServicePreferencesRequest.builder()
                        .globalEndpointTokenVersion(GlobalEndpointTokenVersion.V1_TOKEN)
                        .build());
    }

    /**
     * The whole switch in one test, because it is one account-level flag and separate tests would
     * race each other. The asymmetry is the point: enabling twice is FeatureEnabled, disabling
     * twice is FeatureDisabled.
     */
    @Test
    @Order(4)
    @DisplayName("Outbound federation refuses both redundant directions, with typed errors")
    void federationSwitch() {
        IamClient client = iam();
        // Start from a known state, however an earlier run left it.
        try {
            client.disableOutboundWebIdentityFederation(
                    DisableOutboundWebIdentityFederationRequest.builder().build());
        } catch (FeatureDisabledException expected) {
            // already disabled, which is the state this test wants
        }

        assertThatThrownBy(() -> client.getOutboundWebIdentityFederationInfo(
                GetOutboundWebIdentityFederationInfoRequest.builder().build()))
                .isInstanceOf(FeatureDisabledException.class);

        String issuer = client.enableOutboundWebIdentityFederation(
                        EnableOutboundWebIdentityFederationRequest.builder().build())
                .issuerIdentifier();
        assertThat(issuer)
                .as("the issuer is a per-account host under tokens.sts.global")
                .matches("https://[0-9a-f-]{36}\\.tokens\\.sts\\.global\\..+");

        assertThatThrownBy(() -> client.enableOutboundWebIdentityFederation(
                EnableOutboundWebIdentityFederationRequest.builder().build()))
                .isInstanceOf(FeatureEnabledException.class);

        GetOutboundWebIdentityFederationInfoResponse info =
                client.getOutboundWebIdentityFederationInfo(
                        GetOutboundWebIdentityFederationInfoRequest.builder().build());
        assertThat(info.issuerIdentifier()).isEqualTo(issuer);
        assertThat(info.jwtVendingEnabled()).isTrue();

        client.disableOutboundWebIdentityFederation(
                DisableOutboundWebIdentityFederationRequest.builder().build());
        assertThatThrownBy(() -> client.disableOutboundWebIdentityFederation(
                DisableOutboundWebIdentityFederationRequest.builder().build()))
                .isInstanceOf(FeatureDisabledException.class);

        // Re-enabling returns the same issuer: a relying party has pinned it.
        String reissued = client.enableOutboundWebIdentityFederation(
                        EnableOutboundWebIdentityFederationRequest.builder().build())
                .issuerIdentifier();
        assertThat(reissued).isEqualTo(issuer);

        client.disableOutboundWebIdentityFederation(
                DisableOutboundWebIdentityFederationRequest.builder().build());
    }
}
