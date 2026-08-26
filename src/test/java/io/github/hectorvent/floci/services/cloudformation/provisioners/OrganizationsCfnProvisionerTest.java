package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.organizations.OrganizationsService;
import io.github.hectorvent.floci.services.organizations.model.CreateAccountStatus;
import io.github.hectorvent.floci.services.organizations.model.Organization;
import io.github.hectorvent.floci.services.organizations.model.OrganizationAccount;
import io.github.hectorvent.floci.services.organizations.model.OrganizationPolicy;
import io.github.hectorvent.floci.services.organizations.model.OrganizationalUnit;
import io.github.hectorvent.floci.services.organizations.model.Root;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * The Organizations CFN provisioner in isolation, with only {@link OrganizationsService} mocked.
 *
 * <p>The assertions name the exact {@code Fn::GetAtt} keys rather than just checking that
 * provisioning succeeded: an unmapped attribute silently resolves to the literal
 * {@code "LogicalId.Attr"}, so a status-only assertion cannot tell a wired type from an unwired one.
 */
class OrganizationsCfnProvisionerTest {

    private static final String ACCOUNT = "000000000000";
    private static final String ORG_ID = "o-abcdefghij";
    private static final String ROOT_ID = "r-ab12";
    private static final String OU_PATH = ORG_ID + "/" + ROOT_ID + "/ou-ab12-abcd1234/";
    private static final String ACCOUNT_PATH = ORG_ID + "/" + ROOT_ID + "/123456789012/";

    private final OrganizationsService organizations = mock(OrganizationsService.class);
    private final OrganizationsCfnProvisioner provisioner = new OrganizationsCfnProvisioner(organizations);
    private final ObjectMapper mapper = new ObjectMapper();

    private ProvisionContext ctx() {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        when(engine.resolveJsonAttribute(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node.isTextual() ? node.asText() : node.toString();
        });
        return new ProvisionContext(engine, "us-east-1", ACCOUNT, "my-stack");
    }

    private StackResource resource(String type, String logicalId) {
        StackResource r = new StackResource();
        r.setLogicalId(logicalId);
        r.setResourceType(type);
        r.setAttributes(new HashMap<>());
        return r;
    }

    private Organization organization(String featureSet) {
        Organization organization = new Organization();
        organization.setId(ORG_ID);
        organization.setArn("arn:aws:organizations::" + ACCOUNT + ":organization/" + ORG_ID);
        organization.setFeatureSet(featureSet);
        organization.setMasterAccountId(ACCOUNT);
        organization.setMasterAccountArn("arn:aws:organizations::" + ACCOUNT + ":account/" + ORG_ID + "/" + ACCOUNT);
        organization.setMasterAccountEmail("master@example.com");
        Root root = new Root();
        root.setId(ROOT_ID);
        root.setArn("arn:aws:organizations::" + ACCOUNT + ":root/" + ORG_ID + "/" + ROOT_ID);
        organization.setRoot(root);
        return organization;
    }

    // ──────────────────────────── Organization ────────────────────────────

    @Test
    void organizationSetsPhysicalIdAndEveryGetAttAttribute() {
        when(organizations.createOrganization(ACCOUNT, "ALL")).thenReturn(organization("ALL"));
        StackResource r = resource("AWS::Organizations::Organization", "Org");

        provisioner.provision(r, mapper.createObjectNode().put("FeatureSet", "ALL"), ctx());

        assertEquals(ACCOUNT, r.getPhysicalId());
        assertEquals(ORG_ID, r.getAttributes().get("Id"));
        assertEquals("arn:aws:organizations::" + ACCOUNT + ":organization/" + ORG_ID,
                r.getAttributes().get("Arn"));
        assertEquals(ACCOUNT, r.getAttributes().get("ManagementAccountId"));
        assertEquals("arn:aws:organizations::" + ACCOUNT + ":account/" + ORG_ID + "/" + ACCOUNT,
                r.getAttributes().get("ManagementAccountArn"));
        assertEquals("master@example.com", r.getAttributes().get("ManagementAccountEmail"));
        assertEquals(ROOT_ID, r.getAttributes().get("RootId"));
    }

    @Test
    void organizationUpdateUpgradesConsolidatedBillingToAllFeatures() {
        when(organizations.describeOrganization(ACCOUNT))
                .thenReturn(organization("CONSOLIDATED_BILLING"), organization("ALL"));
        StackResource r = resource("AWS::Organizations::Organization", "Org");
        r.setPhysicalId(ACCOUNT);

        provisioner.provision(r, mapper.createObjectNode().put("FeatureSet", "ALL"), ctx());

        verify(organizations).enableAllFeatures(ACCOUNT);
        verify(organizations, never()).createOrganization(anyString(), anyString());
        assertEquals(ACCOUNT, r.getPhysicalId());
    }

    @Test
    void organizationUpdateLeavesAnAlreadyUpgradedOrganizationAlone() {
        when(organizations.describeOrganization(ACCOUNT)).thenReturn(organization("ALL"));
        StackResource r = resource("AWS::Organizations::Organization", "Org");
        r.setPhysicalId(ACCOUNT);

        provisioner.provision(r, mapper.createObjectNode().put("FeatureSet", "ALL"), ctx());

        verify(organizations, never()).enableAllFeatures(anyString());
    }

    // ──────────────────────────── Organizational unit ────────────────────────────

    @Test
    void organizationalUnitSetsPhysicalIdAndGetAttAttributes() {
        OrganizationalUnit unit = new OrganizationalUnit();
        unit.setId("ou-ab12-abcd1234");
        unit.setArn("arn:aws:organizations::" + ACCOUNT + ":ou/" + ORG_ID + "/ou-ab12-abcd1234");
        unit.setName("Workloads");
        when(organizations.createOrganizationalUnit(eq(ACCOUNT), eq(ROOT_ID), eq("Workloads"), any()))
                .thenReturn(unit);
        when(organizations.organizationPath(ACCOUNT, "ou-ab12-abcd1234")).thenReturn(OU_PATH);

        StackResource r = resource("AWS::Organizations::OrganizationalUnit", "Ou");
        ObjectNode props = mapper.createObjectNode().put("Name", "Workloads").put("ParentId", ROOT_ID);

        provisioner.provision(r, props, ctx());

        assertEquals("ou-ab12-abcd1234", r.getPhysicalId());
        assertEquals("ou-ab12-abcd1234", r.getAttributes().get("Id"));
        assertEquals("arn:aws:organizations::" + ACCOUNT + ":ou/" + ORG_ID + "/ou-ab12-abcd1234",
                r.getAttributes().get("Arn"));
        assertEquals(OU_PATH, r.getAttributes().get("Path"));
    }

    @Test
    void anOuWhosePathLookupFailsStaysOwnedByTheStackForRollback() {
        OrganizationalUnit unit = new OrganizationalUnit();
        unit.setId("ou-ab12-abcd1234");
        when(organizations.createOrganizationalUnit(eq(ACCOUNT), eq(ROOT_ID), eq("Workloads"), any()))
                .thenReturn(unit);
        when(organizations.organizationPath(ACCOUNT, "ou-ab12-abcd1234"))
                .thenThrow(new AwsException("AWSOrganizationsNotInUseException", "gone", 400));

        StackResource r = resource("AWS::Organizations::OrganizationalUnit", "Ou");
        ObjectNode props = mapper.createObjectNode().put("Name", "Workloads").put("ParentId", ROOT_ID);

        assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx()));

        // The OU is a real member of the organization by then. CloudFormationService only deletes a
        // CREATE_FAILED resource carrying both markers, so losing either strands the OU.
        assertEquals("ou-ab12-abcd1234", r.getPhysicalId());
        assertEquals("true", r.getAttributes().get(CfnRollback.ROLLBACK_OWNED_ATTR));
    }

    @Test
    void organizationalUnitUpdateRenamesInsteadOfCreating() {
        OrganizationalUnit unit = new OrganizationalUnit();
        unit.setId("ou-ab12-abcd1234");
        unit.setArn("arn:aws:organizations::" + ACCOUNT + ":ou/" + ORG_ID + "/ou-ab12-abcd1234");
        unit.setName("Renamed");
        when(organizations.updateOrganizationalUnit(ACCOUNT, "ou-ab12-abcd1234", "Renamed")).thenReturn(unit);

        StackResource r = resource("AWS::Organizations::OrganizationalUnit", "Ou");
        r.setPhysicalId("ou-ab12-abcd1234");
        ObjectNode props = mapper.createObjectNode().put("Name", "Renamed").put("ParentId", ROOT_ID);

        provisioner.provision(r, props, ctx());

        verify(organizations).updateOrganizationalUnit(ACCOUNT, "ou-ab12-abcd1234", "Renamed");
        verify(organizations, never()).createOrganizationalUnit(anyString(), anyString(), anyString(), any());
        assertEquals("ou-ab12-abcd1234", r.getPhysicalId());
    }

    // ──────────────────────────── Account ────────────────────────────

    private OrganizationAccount account(String parentId) {
        OrganizationAccount account = new OrganizationAccount();
        account.setId("123456789012");
        account.setArn("arn:aws:organizations::" + ACCOUNT + ":account/" + ORG_ID + "/123456789012");
        account.setName("Dev");
        account.setEmail("dev@example.com");
        account.setStatus("ACTIVE");
        account.setJoinedMethod("CREATED");
        account.setJoinedTimestamp(Instant.parse("2026-08-23T00:00:00Z"));
        account.setParentId(parentId);
        return account;
    }

    @Test
    void accountSetsPhysicalIdAndEveryGetAttAttribute() {
        CreateAccountStatus status = new CreateAccountStatus();
        status.setId("car-abcd1234");
        status.setState("SUCCEEDED");
        status.setAccountId("123456789012");
        when(organizations.createAccount(eq(ACCOUNT), eq("dev@example.com"), eq("Dev"), any(), eq(false)))
                .thenReturn(status);
        when(organizations.describeAccount(ACCOUNT, "123456789012")).thenReturn(account(ROOT_ID));
        when(organizations.organizationPath(ACCOUNT, "123456789012")).thenReturn(ACCOUNT_PATH);

        StackResource r = resource("AWS::Organizations::Account", "Dev");
        ObjectNode props = mapper.createObjectNode()
                .put("AccountName", "Dev").put("Email", "dev@example.com");

        provisioner.provision(r, props, ctx());

        assertEquals("123456789012", r.getPhysicalId());
        assertEquals("123456789012", r.getAttributes().get("AccountId"));
        assertEquals("arn:aws:organizations::" + ACCOUNT + ":account/" + ORG_ID + "/123456789012",
                r.getAttributes().get("Arn"));
        assertEquals("ACTIVE", r.getAttributes().get("Status"));
        assertEquals("ACTIVE", r.getAttributes().get("State"));
        assertEquals("CREATED", r.getAttributes().get("JoinedMethod"));
        assertEquals("2026-08-23T00:00:00Z", r.getAttributes().get("JoinedTimestamp"));
        assertEquals(ACCOUNT_PATH, r.getAttributes().get("Paths"));
    }

    @Test
    void accountPathsNameTheParentTheAccountWasMovedInto() {
        CreateAccountStatus status = new CreateAccountStatus();
        status.setState("SUCCEEDED");
        status.setAccountId("123456789012");
        when(organizations.createAccount(eq(ACCOUNT), anyString(), anyString(), any(), eq(false)))
                .thenReturn(status);
        when(organizations.describeAccount(ACCOUNT, "123456789012"))
                .thenReturn(account(ROOT_ID), account("ou-ab12-abcd1234"));
        when(organizations.organizationPath(ACCOUNT, "123456789012")).thenReturn(OU_PATH + "123456789012/");

        StackResource r = resource("AWS::Organizations::Account", "Dev");
        ObjectNode props = mapper.createObjectNode()
                .put("AccountName", "Dev").put("Email", "dev@example.com");
        props.putArray("ParentIds").add("ou-ab12-abcd1234");

        provisioner.provision(r, props, ctx());

        // Accounts are always created under the root and moved afterwards, so reading the path
        // before the move would publish the root path for an account that no longer lives there.
        InOrder order = inOrder(organizations);
        order.verify(organizations).moveAccount(ACCOUNT, "123456789012", ROOT_ID, "ou-ab12-abcd1234");
        order.verify(organizations).organizationPath(ACCOUNT, "123456789012");
        assertEquals(OU_PATH + "123456789012/", r.getAttributes().get("Paths"));
    }

    @Test
    void accountWithParentIdsIsMovedOutOfTheRoot() {
        CreateAccountStatus status = new CreateAccountStatus();
        status.setState("SUCCEEDED");
        status.setAccountId("123456789012");
        when(organizations.createAccount(eq(ACCOUNT), anyString(), anyString(), any(), eq(false)))
                .thenReturn(status);
        when(organizations.describeAccount(ACCOUNT, "123456789012"))
                .thenReturn(account(ROOT_ID), account("ou-ab12-abcd1234"));

        StackResource r = resource("AWS::Organizations::Account", "Dev");
        ObjectNode props = mapper.createObjectNode()
                .put("AccountName", "Dev").put("Email", "dev@example.com");
        props.putArray("ParentIds").add("ou-ab12-abcd1234");

        provisioner.provision(r, props, ctx());

        verify(organizations).moveAccount(ACCOUNT, "123456789012", ROOT_ID, "ou-ab12-abcd1234");
    }

    @Test
    void accountAlreadyInTheRequestedParentIsNotMoved() {
        CreateAccountStatus status = new CreateAccountStatus();
        status.setState("SUCCEEDED");
        status.setAccountId("123456789012");
        when(organizations.createAccount(eq(ACCOUNT), anyString(), anyString(), any(), eq(false)))
                .thenReturn(status);
        when(organizations.describeAccount(ACCOUNT, "123456789012")).thenReturn(account("ou-ab12-abcd1234"));

        StackResource r = resource("AWS::Organizations::Account", "Dev");
        ObjectNode props = mapper.createObjectNode()
                .put("AccountName", "Dev").put("Email", "dev@example.com");
        props.putArray("ParentIds").add("ou-ab12-abcd1234");

        provisioner.provision(r, props, ctx());

        verify(organizations, never()).moveAccount(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void anAccountThatFailsToMoveStillCarriesItsPhysicalIdForRollback() {
        CreateAccountStatus status = new CreateAccountStatus();
        status.setState("SUCCEEDED");
        status.setAccountId("123456789012");
        when(organizations.createAccount(eq(ACCOUNT), anyString(), anyString(), any(), eq(false)))
                .thenReturn(status);
        when(organizations.describeAccount(ACCOUNT, "123456789012")).thenReturn(account(ROOT_ID));
        // A ParentIds entry pointing at an OU that isn't there — a broken Ref or a stale literal.
        doThrow(new AwsException("DestinationParentNotFoundException", "no such parent", 400))
                .when(organizations).moveAccount(anyString(), anyString(), anyString(), anyString());

        StackResource r = resource("AWS::Organizations::Account", "Dev");
        ObjectNode props = mapper.createObjectNode()
                .put("AccountName", "Dev").put("Email", "dev@example.com");
        props.putArray("ParentIds").add("ou-ab12-missing1");

        assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx()));

        // CreateAccount already succeeded, so the account is a real member of the organization.
        // Without the physical id, CloudFormation's rollback has nothing to call delete() with and
        // the account is stranded outside the stack.
        assertEquals("123456789012", r.getPhysicalId());
    }

    @Test
    void aPolicyThatFailsToAttachStillCarriesItsPhysicalIdForRollback() {
        when(organizations.createPolicy(eq(ACCOUNT), anyString(), any(), anyString(), anyString(), any()))
                .thenReturn(policy());
        doThrow(new AwsException("TargetNotFoundException", "no such target", 400))
                .when(organizations).attachPolicy(anyString(), anyString(), anyString());

        StackResource r = resource("AWS::Organizations::Policy", "Scp");
        ObjectNode props = mapper.createObjectNode()
                .put("Name", "scp").put("Type", "SERVICE_CONTROL_POLICY").put("Content", "{}");
        props.putArray("TargetIds").add("ou-ab12-missing1");

        assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx()));

        assertEquals("p-abcd1234", r.getPhysicalId());
    }

    @Test
    void aFailedCreateAccountFailsTheResource() {
        CreateAccountStatus status = new CreateAccountStatus();
        status.setState("FAILED");
        status.setFailureReason("EMAIL_ALREADY_EXISTS");
        when(organizations.createAccount(eq(ACCOUNT), anyString(), anyString(), any(), eq(false)))
                .thenReturn(status);

        StackResource r = resource("AWS::Organizations::Account", "Dev");
        ObjectNode props = mapper.createObjectNode()
                .put("AccountName", "Dev").put("Email", "dev@example.com");

        AwsException error = assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx()));
        assertEquals("ConstraintViolationException", error.getErrorCode());
    }

    // ──────────────────────────── Policy ────────────────────────────

    private OrganizationPolicy policy(String... targets) {
        OrganizationPolicy policy = new OrganizationPolicy();
        policy.setId("p-abcd1234");
        policy.setArn("arn:aws:organizations::" + ACCOUNT + ":policy/" + ORG_ID
                + "/service_control_policy/p-abcd1234");
        policy.setName("DenyEc2");
        policy.setType("SERVICE_CONTROL_POLICY");
        policy.getTargets().addAll(List.of(targets));
        return policy;
    }

    @Test
    void policySetsPhysicalIdAndGetAttAttributesAndAttachesTargets() {
        when(organizations.createPolicy(eq(ACCOUNT), anyString(), any(), eq("DenyEc2"),
                eq("SERVICE_CONTROL_POLICY"), any())).thenReturn(policy());

        StackResource r = resource("AWS::Organizations::Policy", "Scp");
        ObjectNode props = mapper.createObjectNode()
                .put("Name", "DenyEc2").put("Type", "SERVICE_CONTROL_POLICY");
        props.putObject("Content").put("Version", "2012-10-17");
        props.putArray("TargetIds").add(ROOT_ID);

        provisioner.provision(r, props, ctx());

        assertEquals("p-abcd1234", r.getPhysicalId());
        assertEquals("p-abcd1234", r.getAttributes().get("Id"));
        assertEquals("arn:aws:organizations::" + ACCOUNT + ":policy/" + ORG_ID
                + "/service_control_policy/p-abcd1234", r.getAttributes().get("Arn"));
        assertEquals("false", r.getAttributes().get("AwsManaged"));
        verify(organizations).attachPolicy(ACCOUNT, "p-abcd1234", ROOT_ID);
    }

    @Test
    void policyContentGivenAsAnInlineObjectIsSerializedForTheService() {
        when(organizations.createPolicy(eq(ACCOUNT), eq("{\"Version\":\"2012-10-17\"}"), any(),
                anyString(), anyString(), any())).thenReturn(policy());

        StackResource r = resource("AWS::Organizations::Policy", "Scp");
        ObjectNode props = mapper.createObjectNode()
                .put("Name", "DenyEc2").put("Type", "SERVICE_CONTROL_POLICY");
        props.putObject("Content").put("Version", "2012-10-17");

        provisioner.provision(r, props, ctx());

        verify(organizations).createPolicy(ACCOUNT, "{\"Version\":\"2012-10-17\"}", null,
                "DenyEc2", "SERVICE_CONTROL_POLICY", null);
    }

    @Test
    void policyUpdateAttachesNewTargetsAndDetachesDroppedOnes() {
        when(organizations.updatePolicy(eq(ACCOUNT), eq("p-abcd1234"), anyString(), any(), anyString()))
                .thenReturn(policy(ROOT_ID, "ou-ab12-stale111"));
        when(organizations.listTagsForResource(ACCOUNT, "p-abcd1234")).thenReturn(Map.of());

        StackResource r = resource("AWS::Organizations::Policy", "Scp");
        r.setPhysicalId("p-abcd1234");
        ObjectNode props = mapper.createObjectNode()
                .put("Name", "DenyEc2").put("Type", "SERVICE_CONTROL_POLICY");
        props.putObject("Content").put("Version", "2012-10-17");
        props.putArray("TargetIds").add(ROOT_ID).add("ou-ab12-newone11");

        provisioner.provision(r, props, ctx());

        verify(organizations).attachPolicy(ACCOUNT, "p-abcd1234", "ou-ab12-newone11");
        verify(organizations).detachPolicy(ACCOUNT, "p-abcd1234", "ou-ab12-stale111");
        verify(organizations, never()).attachPolicy(ACCOUNT, "p-abcd1234", ROOT_ID);
    }

    // ──────────────────────────── Resource policy ────────────────────────────

    @Test
    void resourcePolicySetsPhysicalIdAndGetAttAttributes() {
        when(organizations.putResourcePolicy(eq(ACCOUNT), anyString(), any()))
                .thenReturn(new OrganizationsService.ResourcePolicyView("rp-abcd1234",
                        "arn:aws:organizations::" + ACCOUNT + ":resourcepolicy/" + ORG_ID + "/rp-abcd1234",
                        "{}", Map.of()));

        StackResource r = resource("AWS::Organizations::ResourcePolicy", "Rp");
        ObjectNode props = mapper.createObjectNode();
        props.putObject("Content").put("Version", "2012-10-17");

        provisioner.provision(r, props, ctx());

        assertEquals("rp-abcd1234", r.getPhysicalId());
        assertEquals("rp-abcd1234", r.getAttributes().get("Id"));
        assertEquals("arn:aws:organizations::" + ACCOUNT + ":resourcepolicy/" + ORG_ID + "/rp-abcd1234",
                r.getAttributes().get("Arn"));
    }

    @Test
    void resourcePolicyUpdatePassesTheFullTagSetSoDroppedKeysAreRemoved() {
        when(organizations.putResourcePolicy(eq(ACCOUNT), anyString(), any()))
                .thenReturn(new OrganizationsService.ResourcePolicyView("rp-abcd1234",
                        "arn:aws:organizations::" + ACCOUNT + ":resourcepolicy/" + ORG_ID + "/rp-abcd1234",
                        "{}", Map.of()));

        StackResource r = resource("AWS::Organizations::ResourcePolicy", "Rp");
        r.setPhysicalId("rp-abcd1234");
        ObjectNode props = mapper.createObjectNode();
        props.putObject("Content").put("Version", "2012-10-17");
        // The previous revision also carried "owner"; this one drops it.
        props.putArray("Tags").addObject().put("Key", "env").put("Value", "prod");

        provisioner.provision(r, props, ctx());

        // putResourcePolicy replaces the tag map, so handing it exactly the template's set is what
        // removes the dropped key. The other three types reach the same end via replaceTags, which
        // cannot be used here because a resource policy is not a TagResource target.
        verify(organizations).putResourcePolicy(eq(ACCOUNT), anyString(), eq(Map.of("env", "prod")));
    }

    // ──────────────────────────── Delete ────────────────────────────

    @Test
    void deleteResolvesTheOwningManagementAccountFromThePhysicalId() {
        when(organizations.findManagementAccountForResource("ou-ab12-abcd1234"))
                .thenReturn(Optional.of(ACCOUNT));

        provisioner.delete("AWS::Organizations::OrganizationalUnit", "ou-ab12-abcd1234", "us-east-1");

        verify(organizations).deleteOrganizationalUnit(ACCOUNT, "ou-ab12-abcd1234");
    }

    @Test
    void deletingAPolicyDetachesItsTargetsFirst() {
        when(organizations.findManagementAccountForResource("p-abcd1234")).thenReturn(Optional.of(ACCOUNT));
        when(organizations.listTargetsForPolicy(ACCOUNT, "p-abcd1234")).thenReturn(List.of(
                new OrganizationsService.PolicyTarget(ROOT_ID, "arn:root", "Root", "ROOT")));

        provisioner.delete("AWS::Organizations::Policy", "p-abcd1234", "us-east-1");

        verify(organizations).detachPolicy(ACCOUNT, "p-abcd1234", ROOT_ID);
        verify(organizations).deletePolicy(ACCOUNT, "p-abcd1234");
    }

    @Test
    void deletingAnAlreadyGoneResourceIsANoOp() {
        when(organizations.findManagementAccountForResource("ou-ab12-abcd1234")).thenReturn(Optional.empty());

        provisioner.delete("AWS::Organizations::OrganizationalUnit", "ou-ab12-abcd1234", "us-east-1");

        verify(organizations).findManagementAccountForResource("ou-ab12-abcd1234");
        verifyNoMoreInteractions(organizations);
    }

    @Test
    void deleteToleratesAResourceThatDisappearedBetweenLookupAndDelete() {
        when(organizations.findManagementAccountForResource("ou-ab12-abcd1234"))
                .thenReturn(Optional.of(ACCOUNT));
        org.mockito.Mockito.doThrow(new AwsException("OrganizationalUnitNotFoundException", "gone", 400))
                .when(organizations).deleteOrganizationalUnit(ACCOUNT, "ou-ab12-abcd1234");

        provisioner.delete("AWS::Organizations::OrganizationalUnit", "ou-ab12-abcd1234", "us-east-1");
    }

    @Test
    void deleteRethrowsARealFailure() {
        when(organizations.findManagementAccountForResource("ou-ab12-abcd1234"))
                .thenReturn(Optional.of(ACCOUNT));
        org.mockito.Mockito.doThrow(new AwsException("OrganizationalUnitNotEmptyException", "busy", 400))
                .when(organizations).deleteOrganizationalUnit(ACCOUNT, "ou-ab12-abcd1234");

        AwsException error = assertThrows(AwsException.class, () -> provisioner.delete(
                "AWS::Organizations::OrganizationalUnit", "ou-ab12-abcd1234", "us-east-1"));
        assertEquals("OrganizationalUnitNotEmptyException", error.getErrorCode());
    }

    @Test
    void everyRegistrySchemaTypeIsServed() {
        assertEquals(Set.of(
                        "AWS::Organizations::Organization",
                        "AWS::Organizations::OrganizationalUnit",
                        "AWS::Organizations::Account",
                        "AWS::Organizations::Policy",
                        "AWS::Organizations::ResourcePolicy"),
                provisioner.resourceTypes());
    }
}
