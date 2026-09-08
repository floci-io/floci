package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudwatch.dashboards.CloudWatchDashboardsService;
import io.github.hectorvent.floci.services.cloudwatch.dashboards.model.Dashboard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@code AWS::CloudWatch::Dashboard}: the name is the physical id and create-only, the body is
 * put on create and on every update, tags are driven to the template's set on update, and a
 * changed name is a replacement whose displaced dashboard is left to the cleanup record.
 */
class CloudWatchDashboardCfnProvisionerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_ID = "000000000000";
    private static final String STACK = "my-stack";
    private static final String TYPE = "AWS::CloudWatch::Dashboard";
    private static final String BODY = "{\"widgets\":[{\"type\":\"text\",\"properties\":{\"markdown\":\"hi\"}}]}";

    private CloudWatchDashboardsService dashboards;
    private CloudWatchDashboardCfnProvisioner provisioner;
    private CloudFormationTemplateEngine engine;

    @BeforeEach
    void setUp() {
        dashboards = mock(CloudWatchDashboardsService.class);
        provisioner = new CloudWatchDashboardCfnProvisioner(dashboards);
        engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(i -> {
            JsonNode node = i.getArgument(0);
            return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(i -> i.getArgument(0));
        when(engine.resolveJsonAttribute(any())).thenAnswer(i -> {
            JsonNode node = i.getArgument(0);
            if (node == null || node.isMissingNode() || node.isNull()) {
                return null;
            }
            return node.isTextual() ? node.asText() : node.toString();
        });
        when(dashboards.putDashboard(anyString(), anyString(), anyMap(), eq(REGION))).thenAnswer(i ->
                new Dashboard(i.getArgument(0), arn(i.getArgument(0)), i.getArgument(1)));
        when(dashboards.listTagsForResource(anyString(), eq(REGION))).thenReturn(Map.of());
    }

    private static String arn(String name) {
        return "arn:aws:cloudwatch::" + ACCOUNT_ID + ":dashboard/" + name;
    }

    private static JsonNode props(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static StackResource resource() {
        StackResource r = new StackResource();
        r.setLogicalId("Dashboard");
        r.setResourceType(TYPE);
        r.setAttributes(new HashMap<>());
        return r;
    }

    private StackResource provision(String json, String priorPhysicalId) {
        StackResource r = resource();
        r.setPhysicalId(priorPhysicalId);
        provisioner.provision(r, props(json),
                new ProvisionContext(engine, REGION, ACCOUNT_ID, STACK, priorPhysicalId));
        return r;
    }

    @Test
    void servesOnlyTheDashboardType() {
        assertEquals(Set.of(TYPE), provisioner.resourceTypes());
    }

    @Test
    void createPutsTheDashboardUnderTheExplicitName() {
        StackResource r = provision("""
                {"DashboardName": "ops", "DashboardBody": %s}
                """.formatted(MAPPER.valueToTree(BODY)), null);

        verify(dashboards).putDashboard("ops", BODY, Map.of(), REGION);
        assertEquals("ops", r.getPhysicalId(), "Ref is the dashboard name");
        assertTrue(r.getAttributes().isEmpty(), "the schema declares no Fn::GetAtt attributes");
        assertFalse(provisioner.hasReplacementUpdate(r));
    }

    /** CloudFormation takes the body as a string; a template can still write it as JSON. */
    @Test
    void aBodyGivenAsAnObjectIsSerialised() {
        StackResource r = provision("""
                {"DashboardName": "ops", "DashboardBody": {"widgets": []}}
                """, null);

        verify(dashboards).putDashboard("ops", "{\"widgets\":[]}", Map.of(), REGION);
        assertEquals("ops", r.getPhysicalId());
    }

    @Test
    void anUnnamedDashboardGetsAGeneratedNameThatLaterUpdatesKeep() {
        StackResource created = provision("""
                {"DashboardBody": "{}"}
                """, null);
        String generated = created.getPhysicalId();
        assertTrue(generated.startsWith("my-stack-Dashboard-"), generated);

        StackResource updated = provision("""
                {"DashboardBody": "{\\"widgets\\":[]}"}
                """, generated);

        assertEquals(generated, updated.getPhysicalId(), "an unnamed dashboard keeps its name across updates");
        verify(dashboards).putDashboard(generated, "{\"widgets\":[]}", Map.of(), REGION);
        assertFalse(provisioner.hasReplacementUpdate(updated));
    }

    @Test
    void dashboardBodyIsRequired() {
        AwsException e = assertThrows(AwsException.class, () -> provision("""
                {"DashboardName": "ops"}
                """, null));

        assertEquals("ValidationError", e.getErrorCode());
        assertTrue(e.getMessage().contains("DashboardBody"), e.getMessage());
        verifyNoInteractions(dashboards);
    }

    @Test
    void aNameLongerThanTheSchemaAllowsIsRejected() {
        AwsException e = assertThrows(AwsException.class, () -> provision("""
                {"DashboardName": "%s", "DashboardBody": "{}"}
                """.formatted("n".repeat(256)), null));

        assertEquals("ValidationError", e.getErrorCode());
        assertTrue(e.getMessage().contains("DashboardName"), e.getMessage());
        verifyNoInteractions(dashboards);
    }

    @Test
    void tagsAreAppliedOnCreate() {
        ArgumentCaptor<Map<String, String>> tags = ArgumentCaptor.captor();

        provision("""
                {"DashboardName": "ops", "DashboardBody": "{}",
                 "Tags": [{"Key": "team", "Value": "platform"}, {"Key": "env", "Value": "dev"}]}
                """, null);

        verify(dashboards).putDashboard(eq("ops"), eq("{}"), tags.capture(), eq(REGION));
        assertEquals(Map.of("team", "platform", "env", "dev"), tags.getValue());
        verify(dashboards, never()).tagResource(anyString(), anyMap(), anyString());
        verify(dashboards, never()).untagResource(anyString(), anyList(), anyString());
    }

    /** PutDashboard keeps a dashboard's tags when it replaces it, so the update reconciles them itself. */
    @Test
    void anUpdateDrivesTheTagsToTheTemplate() {
        when(dashboards.listTagsForResource(arn("ops"), REGION))
                .thenReturn(Map.of("team", "platform", "stale", "gone"));

        StackResource r = provision("""
                {"DashboardName": "ops", "DashboardBody": "{}",
                 "Tags": [{"Key": "team", "Value": "data"}, {"Key": "env", "Value": "dev"}]}
                """, "ops");

        verify(dashboards).putDashboard(eq("ops"), eq("{}"), anyMap(), eq(REGION));
        verify(dashboards).untagResource(arn("ops"), List.of("stale"), REGION);
        verify(dashboards).tagResource(arn("ops"), Map.of("team", "data", "env", "dev"), REGION);
        assertEquals("ops", r.getPhysicalId());
        assertFalse(provisioner.hasReplacementUpdate(r));
    }

    @Test
    void anUpdateWithNoTagsRemovesTheStoredOnes() {
        when(dashboards.listTagsForResource(arn("ops"), REGION)).thenReturn(Map.of("team", "platform"));

        provision("""
                {"DashboardName": "ops", "DashboardBody": "{}"}
                """, "ops");

        verify(dashboards).untagResource(arn("ops"), List.of("team"), REGION);
        verify(dashboards, never()).tagResource(anyString(), anyMap(), anyString());
    }

    @Test
    void anUpdateWithUnchangedTagsRemovesNothing() {
        when(dashboards.listTagsForResource(arn("ops"), REGION)).thenReturn(Map.of("team", "platform"));

        provision("""
                {"DashboardName": "ops", "DashboardBody": "{}",
                 "Tags": [{"Key": "team", "Value": "platform"}]}
                """, "ops");

        verify(dashboards, never()).untagResource(anyString(), anyList(), anyString());
        verify(dashboards).tagResource(arn("ops"), Map.of("team", "platform"), REGION);
    }

    /** DashboardName is create-only: a new name creates a second dashboard and leaves the first to the cleanup. */
    @Test
    void aRenameIsAReplacementThatLeavesThePriorToTheCleanup() {
        StackResource r = provision("""
                {"DashboardName": "ops-v2", "DashboardBody": "{}", "Tags": [{"Key": "team", "Value": "platform"}]}
                """, "ops");

        verify(dashboards).putDashboard("ops-v2", "{}", Map.of("team", "platform"), REGION);
        verify(dashboards, never()).deleteDashboards(anyList(), anyString());
        verify(dashboards, never()).listTagsForResource(anyString(), anyString());
        assertEquals("ops-v2", r.getPhysicalId());
        assertTrue(provisioner.hasReplacementUpdate(r));
        assertEquals("ops", provisioner.updateCleanupPhysicalId(r));
    }

    @Test
    void completingAReplacementDeletesTheDisplacedDashboard() {
        StackResource r = provision("""
                {"DashboardName": "ops-v2", "DashboardBody": "{}"}
                """, "ops");

        UpdateCleanupResult result = provisioner.completeUpdate(r);

        assertTrue(result.applicable());
        assertTrue(result.complete());
        verify(dashboards).deleteDashboards(List.of("ops"), REGION);
        assertFalse(provisioner.hasReplacementUpdate(r));
    }

    @Test
    void rollingBackAReplacementDeletesTheNewDashboardAndRestoresThePriorName() {
        StackResource r = provision("""
                {"DashboardName": "ops-v2", "DashboardBody": "{}"}
                """, "ops");

        assertTrue(provisioner.rollbackUpdate(r));

        verify(dashboards).deleteDashboards(List.of("ops-v2"), REGION);
        verify(dashboards, never()).deleteDashboards(List.of("ops"), REGION);
        assertEquals("ops", r.getPhysicalId());
        assertFalse(provisioner.hasReplacementUpdate(r));
    }

    @Test
    void anInPlaceUpdateReportsNoRollback() {
        StackResource r = provision("""
                {"DashboardName": "ops", "DashboardBody": "{}"}
                """, "ops");

        assertFalse(provisioner.rollbackUpdate(r));
        assertNull(provisioner.updateCleanupPhysicalId(r));
    }

    @Test
    void aRejectedBodyLeavesThePriorNameInPlace() {
        doThrow(new AwsException("InvalidParameterInput", "The dashboard body is invalid", 400))
                .when(dashboards).putDashboard(eq("ops-v2"), anyString(), anyMap(), eq(REGION));
        StackResource r = resource();
        r.setPhysicalId("ops");

        assertThrows(AwsException.class, () -> provisioner.provision(r, props("""
                {"DashboardName": "ops-v2", "DashboardBody": "not json"}
                """), new ProvisionContext(engine, REGION, ACCOUNT_ID, STACK, "ops")));

        assertEquals("ops", r.getPhysicalId());
        assertFalse(provisioner.hasReplacementUpdate(r));
    }

    @Test
    void deleteRemovesTheDashboard() {
        provisioner.delete(TYPE, "ops", REGION);

        verify(dashboards).deleteDashboards(List.of("ops"), REGION);
    }

    @Test
    void deleteToleratesADashboardThatIsAlreadyGone() {
        doThrow(new AwsException("ResourceNotFound", "Dashboard does not exist: ops", 404))
                .when(dashboards).deleteDashboards(List.of("ops"), REGION);

        provisioner.delete(TYPE, "ops", REGION);
    }

    @Test
    void deletePropagatesAnyOtherFailure() {
        doThrow(new AwsException("InternalServiceError", "boom", 500))
                .when(dashboards).deleteDashboards(List.of("ops"), REGION);

        assertThrows(AwsException.class, () -> provisioner.delete(TYPE, "ops", REGION));
    }

    @Test
    void deleteIgnoresAMissingId() {
        provisioner.delete(TYPE, null, REGION);
        provisioner.delete(TYPE, " ", REGION);

        verifyNoInteractions(dashboards);
    }
}
