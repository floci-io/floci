package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.services.bedrockagentcorecontrol.BedrockAgentCoreControlClient;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Bedrock AgentCore tools")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BedrockAgentCoreToolsTest {

    private static BedrockAgentCoreControlClient client;
    private static String browserName;
    private static String browserId;
    private static String profileName;
    private static String profileId;
    private static String codeInterpreterName;
    private static String codeInterpreterId;

    @BeforeAll
    static void setup() {
        client = TestFixtures.bedrockAgentCoreControlClient();
        browserName = "browser" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        profileName = "profile" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        codeInterpreterName = "code" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    @AfterAll
    static void cleanup() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    @Order(1)
    void createBrowser() {
        CreateBrowserResponse response = client.createBrowser(CreateBrowserRequest.builder()
                .name(browserName)
                .networkConfiguration(BrowserNetworkConfiguration.builder()
                        .networkMode(BrowserNetworkMode.PUBLIC)
                        .build())
                .build());

        browserId = response.browserId();
        assertThat(browserId).startsWith(browserName + "-");
        assertThat(response.browserArn()).contains(":bedrock-agentcore:").contains(":browser-custom/");
        assertThat(response.statusAsString()).isEqualTo("READY");
        assertThat(response.createdAt()).isNotNull();
    }

    @Test
    @Order(2)
    void getBrowser() {
        GetBrowserResponse response = client.getBrowser(GetBrowserRequest.builder()
                .browserId(browserId)
                .build());

        assertThat(response.browserId()).isEqualTo(browserId);
        assertThat(response.name()).isEqualTo(browserName);
        assertThat(response.browserArn()).contains(":browser-custom/");
        assertThat(response.networkConfiguration().networkModeAsString()).isEqualTo("PUBLIC");
        assertThat(response.statusAsString()).isEqualTo("READY");
    }

    @Test
    @Order(3)
    void listBrowsers() {
        ListBrowsersResponse response = client.listBrowsers(ListBrowsersRequest.builder()
                .type(ResourceType.CUSTOM)
                .maxResults(100)
                .build());

        assertThat(response.browserSummaries())
                .anyMatch(summary -> browserId.equals(summary.browserId()));
    }

    @Test
    @Order(4)
    void deleteBrowser() {
        String clientToken = UUID.randomUUID().toString();
        DeleteBrowserRequest request = DeleteBrowserRequest.builder()
                .browserId(browserId)
                .clientToken(clientToken)
                .build();
        DeleteBrowserResponse response = client.deleteBrowser(request);
        DeleteBrowserResponse replay = client.deleteBrowser(request);

        assertThat(response.browserId()).isEqualTo(browserId);
        assertThat(response.statusAsString()).isEqualTo("DELETING");
        assertThat(response.lastUpdatedAt()).isNotNull();
        assertThat(replay.browserId()).isEqualTo(browserId);
        assertThat(replay.statusAsString()).isEqualTo("DELETING");
    }

    @Test
    @Order(5)
    void createBrowserProfile() {
        CreateBrowserProfileResponse response = client.createBrowserProfile(CreateBrowserProfileRequest.builder()
                .name(profileName)
                .description("profile")
                .build());

        profileId = response.profileId();
        assertThat(profileId).startsWith(profileName + "-");
        assertThat(response.profileArn()).contains(":browser-profile/");
        assertThat(response.statusAsString()).isEqualTo("READY");
        assertThat(response.createdAt()).isNotNull();
    }

    @Test
    @Order(6)
    void getBrowserProfile() {
        GetBrowserProfileResponse response = client.getBrowserProfile(GetBrowserProfileRequest.builder()
                .profileId(profileId)
                .build());

        assertThat(response.profileId()).isEqualTo(profileId);
        assertThat(response.name()).isEqualTo(profileName);
        assertThat(response.description()).isEqualTo("profile");
        assertThat(response.statusAsString()).isEqualTo("READY");
    }

    @Test
    @Order(7)
    void listBrowserProfiles() {
        ListBrowserProfilesResponse response = client.listBrowserProfiles(ListBrowserProfilesRequest.builder()
                .name(profileName)
                .maxResults(100)
                .build());

        assertThat(response.profileSummaries())
                .anyMatch(summary -> profileId.equals(summary.profileId()));
    }

    @Test
    @Order(8)
    void deleteBrowserProfile() {
        String clientToken = UUID.randomUUID().toString();
        DeleteBrowserProfileRequest request = DeleteBrowserProfileRequest.builder()
                .profileId(profileId)
                .clientToken(clientToken)
                .build();
        DeleteBrowserProfileResponse response = client.deleteBrowserProfile(request);
        DeleteBrowserProfileResponse replay = client.deleteBrowserProfile(request);

        assertThat(response.profileId()).isEqualTo(profileId);
        assertThat(response.statusAsString()).isEqualTo("DELETING");
        assertThat(response.lastUpdatedAt()).isNotNull();
        assertThat(replay.profileId()).isEqualTo(profileId);
        assertThat(replay.statusAsString()).isEqualTo("DELETING");
    }

    @Test
    @Order(9)
    void createCodeInterpreter() {
        CreateCodeInterpreterResponse response = client.createCodeInterpreter(CreateCodeInterpreterRequest.builder()
                .name(codeInterpreterName)
                .networkConfiguration(CodeInterpreterNetworkConfiguration.builder()
                        .networkMode(CodeInterpreterNetworkMode.PUBLIC)
                        .build())
                .build());

        codeInterpreterId = response.codeInterpreterId();
        assertThat(codeInterpreterId).startsWith(codeInterpreterName + "-");
        assertThat(response.codeInterpreterArn()).contains(":code-interpreter-custom/");
        assertThat(response.statusAsString()).isEqualTo("READY");
        assertThat(response.createdAt()).isNotNull();
    }

    @Test
    @Order(10)
    void getCodeInterpreter() {
        GetCodeInterpreterResponse response = client.getCodeInterpreter(GetCodeInterpreterRequest.builder()
                .codeInterpreterId(codeInterpreterId)
                .build());

        assertThat(response.codeInterpreterId()).isEqualTo(codeInterpreterId);
        assertThat(response.name()).isEqualTo(codeInterpreterName);
        assertThat(response.networkConfiguration().networkModeAsString()).isEqualTo("PUBLIC");
        assertThat(response.statusAsString()).isEqualTo("READY");
    }

    @Test
    @Order(11)
    void listCodeInterpreters() {
        ListCodeInterpretersResponse response = client.listCodeInterpreters(ListCodeInterpretersRequest.builder()
                .type(ResourceType.CUSTOM)
                .maxResults(100)
                .build());

        assertThat(response.codeInterpreterSummaries())
                .anyMatch(summary -> codeInterpreterId.equals(summary.codeInterpreterId()));
    }

    @Test
    @Order(12)
    void deleteCodeInterpreter() {
        String clientToken = UUID.randomUUID().toString();
        DeleteCodeInterpreterRequest request = DeleteCodeInterpreterRequest.builder()
                .codeInterpreterId(codeInterpreterId)
                .clientToken(clientToken)
                .build();
        DeleteCodeInterpreterResponse response = client.deleteCodeInterpreter(request);
        DeleteCodeInterpreterResponse replay = client.deleteCodeInterpreter(request);

        assertThat(response.codeInterpreterId()).isEqualTo(codeInterpreterId);
        assertThat(response.statusAsString()).isEqualTo("DELETING");
        assertThat(response.lastUpdatedAt()).isNotNull();
        assertThat(replay.codeInterpreterId()).isEqualTo(codeInterpreterId);
        assertThat(replay.statusAsString()).isEqualTo("DELETING");
    }
}
