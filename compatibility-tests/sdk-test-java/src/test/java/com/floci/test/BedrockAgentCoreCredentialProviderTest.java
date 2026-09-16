package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import software.amazon.awssdk.services.bedrockagentcorecontrol.BedrockAgentCoreControlClient;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.CreateApiKeyCredentialProviderRequest;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.CreateApiKeyCredentialProviderResponse;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.CreateOauth2CredentialProviderRequest;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.CreateOauth2CredentialProviderResponse;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.CredentialProviderVendorType;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.Oauth2ProviderConfigInput;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.SecretSourceType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Bedrock AgentCore credential providers")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BedrockAgentCoreCredentialProviderTest {

    private static BedrockAgentCoreControlClient client;
    private static String apiKeyProviderName;
    private static String oauth2ProviderName;

    @BeforeAll
    static void setup() {
        client = TestFixtures.bedrockAgentCoreControlClient();
        apiKeyProviderName = "api_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        oauth2ProviderName = "oauth_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    @AfterAll
    static void cleanup() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    @Order(1)
    void createApiKeyCredentialProvider() {
        CreateApiKeyCredentialProviderResponse response = client.createApiKeyCredentialProvider(
                CreateApiKeyCredentialProviderRequest.builder()
                        .name(apiKeyProviderName)
                        .apiKey("secret-value")
                        .apiKeySecretSource(SecretSourceType.MANAGED)
                        .build());

        assertThat(response.name()).isEqualTo(apiKeyProviderName);
        assertThat(response.apiKeySecretSourceAsString()).isEqualTo("MANAGED");
        assertThat(response.credentialProviderArn()).contains(":acps:").contains("/apikeycredentialprovider/");
        assertThat(response.apiKeySecretArn().secretArn()).contains(":secretsmanager:");
        assertThat(response.apiKeySecretJsonKey()).isEqualTo("apiKey");
    }

    @Test
    @Order(2)
    void getApiKeyCredentialProvider() {
        var response = client.getApiKeyCredentialProvider(builder -> builder.name(apiKeyProviderName));

        assertThat(response.name()).isEqualTo(apiKeyProviderName);
        assertThat(response.apiKeySecretSourceAsString()).isEqualTo("MANAGED");
        assertThat(response.credentialProviderArn()).contains("/apikeycredentialprovider/");
        assertThat(response.createdTime()).isNotNull();
        assertThat(response.lastUpdatedTime()).isNotNull();
    }

    @Test
    @Order(3)
    void listApiKeyCredentialProviders() {
        var response = client.listApiKeyCredentialProviders(builder -> builder.maxResults(100));

        assertThat(response.credentialProviders())
                .anyMatch(provider -> apiKeyProviderName.equals(provider.name()));
    }

    @Test
    @Order(4)
    void updateApiKeyCredentialProvider() {
        var response = client.updateApiKeyCredentialProvider(builder -> builder
                .name(apiKeyProviderName)
                .apiKey("rotated-value")
                .apiKeySecretSource(SecretSourceType.MANAGED));

        assertThat(response.name()).isEqualTo(apiKeyProviderName);
        assertThat(response.apiKeySecretSourceAsString()).isEqualTo("MANAGED");
        assertThat(response.lastUpdatedTime()).isNotNull();
    }

    @Test
    @Order(5)
    void deleteApiKeyCredentialProvider() {
        var response = client.deleteApiKeyCredentialProvider(builder -> builder.name(apiKeyProviderName));
        assertThat(response.sdkHttpResponse().statusCode()).isEqualTo(204);
    }

    @Test
    @Order(6)
    void createOauth2CredentialProvider() {
        CreateOauth2CredentialProviderResponse response = client.createOauth2CredentialProvider(
                CreateOauth2CredentialProviderRequest.builder()
                        .name(oauth2ProviderName)
                        .credentialProviderVendor(CredentialProviderVendorType.GITHUB_OAUTH2)
                        .oauth2ProviderConfigInput(Oauth2ProviderConfigInput.fromGithubOauth2ProviderConfig(builder -> builder
                                .clientId("client-id")
                                .clientSecret("client-value")
                                .clientSecretSource(SecretSourceType.MANAGED)))
                        .build());

        assertThat(response.name()).isEqualTo(oauth2ProviderName);
        assertThat(response.credentialProviderArn()).contains("/oauth2credentialprovider/");
        assertThat(response.clientSecretSourceAsString()).isEqualTo("MANAGED");
        assertThat(response.statusAsString()).isEqualTo("READY");
        assertThat(response.oauth2ProviderConfigOutput().githubOauth2ProviderConfig().clientId()).isEqualTo("client-id");
    }

    @Test
    @Order(7)
    void getOauth2CredentialProvider() {
        var response = client.getOauth2CredentialProvider(builder -> builder.name(oauth2ProviderName));

        assertThat(response.name()).isEqualTo(oauth2ProviderName);
        assertThat(response.credentialProviderVendorAsString()).isEqualTo("GithubOauth2");
        assertThat(response.statusAsString()).isEqualTo("READY");
        assertThat(response.createdTime()).isNotNull();
        assertThat(response.lastUpdatedTime()).isNotNull();
        assertThat(response.oauth2ProviderConfigOutput().githubOauth2ProviderConfig().clientId()).isEqualTo("client-id");
    }

    @Test
    @Order(8)
    void listOauth2CredentialProviders() {
        var response = client.listOauth2CredentialProviders(builder -> builder.maxResults(20));

        assertThat(response.credentialProviders())
                .anyMatch(provider -> oauth2ProviderName.equals(provider.name())
                        && "GithubOauth2".equals(provider.credentialProviderVendorAsString()));
    }

    @Test
    @Order(9)
    void updateOauth2CredentialProvider() {
        var response = client.updateOauth2CredentialProvider(builder -> builder
                .name(oauth2ProviderName)
                .credentialProviderVendor(CredentialProviderVendorType.GITHUB_OAUTH2)
                .oauth2ProviderConfigInput(Oauth2ProviderConfigInput.fromGithubOauth2ProviderConfig(config -> config
                        .clientId("updated-client-id")
                        .clientSecret("updated-client-secret")
                        .clientSecretSource(SecretSourceType.MANAGED))));

        assertThat(response.name()).isEqualTo(oauth2ProviderName);
        assertThat(response.statusAsString()).isEqualTo("READY");
        assertThat(response.credentialProviderVendorAsString()).isEqualTo("GithubOauth2");
        assertThat(response.oauth2ProviderConfigOutput().githubOauth2ProviderConfig().clientId())
                .isEqualTo("updated-client-id");
        assertThat(response.lastUpdatedTime()).isNotNull();
    }

    @Test
    @Order(10)
    void deleteOauth2CredentialProvider() {
        var response = client.deleteOauth2CredentialProvider(builder -> builder.name(oauth2ProviderName));
        assertThat(response.sdkHttpResponse().statusCode()).isEqualTo(204);
    }
}
