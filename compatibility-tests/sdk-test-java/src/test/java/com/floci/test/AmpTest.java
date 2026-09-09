package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.services.amp.AmpClient;
import software.amazon.awssdk.services.amp.model.CreateWorkspaceResponse;
import software.amazon.awssdk.services.amp.model.DescribeWorkspaceResponse;
import software.amazon.awssdk.services.amp.model.ListWorkspacesResponse;
import software.amazon.awssdk.services.amp.model.ResourceNotFoundException;
import software.amazon.awssdk.services.amp.model.WorkspaceStatusCode;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Managed Prometheus (AMP)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AmpTest {

    private static AmpClient amp;
    private static String alias;
    private static String workspaceId;
    private static String workspaceArn;

    @BeforeAll
    static void setup() {
        amp = TestFixtures.ampClient();
        alias = "sdk-test-workspace-" + System.currentTimeMillis();
    }

    @AfterAll
    static void cleanup() {
        if (amp != null) {
            if (workspaceId != null) {
                try {
                    amp.deleteWorkspace(r -> r.workspaceId(workspaceId));
                } catch (Exception ignored) {}
            }
            amp.close();
        }
    }

    @Test
    @Order(1)
    void createWorkspace() {
        CreateWorkspaceResponse response = amp.createWorkspace(r -> r
                .alias(alias)
                .tags(Map.of("team", "devops")));

        workspaceId = response.workspaceId();
        workspaceArn = response.arn();

        assertThat(workspaceId).startsWith("ws-");
        assertThat(workspaceArn).contains(":aps:").contains(":workspace/" + workspaceId);
        assertThat(response.status().statusCode()).isEqualTo(WorkspaceStatusCode.ACTIVE);
        assertThat(response.tags()).containsEntry("team", "devops");
    }

    @Test
    @Order(2)
    void describeWorkspace() {
        DescribeWorkspaceResponse response = amp.describeWorkspace(r -> r.workspaceId(workspaceId));

        assertThat(response.workspace().workspaceId()).isEqualTo(workspaceId);
        assertThat(response.workspace().alias()).isEqualTo(alias);
        assertThat(response.workspace().arn()).isEqualTo(workspaceArn);
        assertThat(response.workspace().status().statusCode()).isEqualTo(WorkspaceStatusCode.ACTIVE);
        assertThat(response.workspace().prometheusEndpoint()).contains("/workspaces/" + workspaceId);
        // Proves the wire timestamp is restJson1 epoch-seconds: an ISO string here fails SDK
        // deserialization before any assertion runs.
        assertThat(response.workspace().createdAt()).isNotNull();
        assertThat(response.workspace().tags()).containsEntry("team", "devops");
    }

    @Test
    @Order(3)
    void listWorkspacesByAliasPrefix() {
        ListWorkspacesResponse response = amp.listWorkspaces(r -> r.alias("sdk-test-workspace-"));

        assertThat(response.workspaces())
                .anySatisfy(w -> assertThat(w.workspaceId()).isEqualTo(workspaceId));
    }

    @Test
    @Order(4)
    void updateWorkspaceAlias() {
        amp.updateWorkspaceAlias(r -> r.workspaceId(workspaceId).alias(alias + "-renamed"));

        assertThat(amp.describeWorkspace(r -> r.workspaceId(workspaceId)).workspace().alias())
                .isEqualTo(alias + "-renamed");
    }

    @Test
    @Order(5)
    void tagResourceRoundTrip() {
        amp.tagResource(r -> r.resourceArn(workspaceArn).tags(Map.of("env", "test")));

        assertThat(amp.listTagsForResource(r -> r.resourceArn(workspaceArn)).tags())
                .containsEntry("team", "devops")
                .containsEntry("env", "test");

        amp.untagResource(r -> r.resourceArn(workspaceArn).tagKeys("env"));

        assertThat(amp.listTagsForResource(r -> r.resourceArn(workspaceArn)).tags())
                .containsEntry("team", "devops")
                .doesNotContainKey("env");
    }

    @Test
    @Order(6)
    void deleteWorkspaceThenDescribeThrowsResourceNotFound() {
        amp.deleteWorkspace(r -> r.workspaceId(workspaceId));

        // The terraform/pulumi provider's delete waiter matches this typed exception
        // (errs.IsA[*types.ResourceNotFoundException]) to treat the workspace as gone.
        assertThatThrownBy(() -> amp.describeWorkspace(r -> r.workspaceId(workspaceId)))
                .isInstanceOf(ResourceNotFoundException.class);

        workspaceId = null;
    }
}
