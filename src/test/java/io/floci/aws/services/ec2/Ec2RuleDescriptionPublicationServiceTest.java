package io.floci.aws.services.ec2;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.storage.PersistentStorage;
import io.floci.aws.services.ec2.model.IpPermission;
import io.floci.aws.services.ec2.model.IpRange;
import io.floci.aws.services.ec2.model.Ipv6Range;
import io.floci.aws.services.ec2.model.PrefixListId;
import io.floci.aws.services.ec2.model.SecurityGroup;
import io.floci.aws.services.ec2.model.SecurityGroupRule;
import io.floci.aws.services.ec2.model.UserIdGroupPair;
import io.floci.aws.services.ec2.portforward.Ec2PortForwardManager;
import io.floci.aws.testing.InMemoryStorageFactory;
import io.floci.aws.testing.ParkingStore;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.floci.aws.testing.ParkingStoreAssertions.assertOperationWaitsForParkedStore;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class Ec2RuleDescriptionPublicationServiceTest {
    private static final String REGION = "us-east-1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void capturedDescriptionUpdateCannotRestoreARuleAfterGroupDeletion(boolean egress) throws Exception {
        Fixture fixture = new Fixture(egress, directory);
        JsonNode retainedRules = fixture.retainedRules();
        fixture.rules.parkNextRead();
        assertOperationWaitsForParkedStore(fixture.rules, fixture::modify,
                () -> fixture.service.deleteSecurityGroup(REGION, fixture.group));
        assertEquals(retainedRules, fixture.retainedRules());
        assertEquals(1, fixture.service.describeSecurityGroups(REGION, List.of(fixture.retainedGroup),
                List.of(), Map.of()).size());
        fixture.assertGroupAndRulesAbsent();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void serialDescriptionUpdateIsObservableAndPreservesUnrelatedRules(boolean egress) {
        Fixture fixture = new Fixture(egress, directory);
        JsonNode retainedRules = fixture.retainedRules();
        assertEquals("before", fixture.rule.getDescription());
        fixture.modify();
        assertEquals("after", fixture.service.describeSecurityGroupRules(REGION, List.of(fixture.group),
                List.of(fixture.rule.getSecurityGroupRuleId())).getFirst().getDescription());
        assertEquals(retainedRules, fixture.retainedRules());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void updateAfterDeletionDoesNotRecreateTheRule(boolean egress) {
        Fixture fixture = new Fixture(egress, directory);
        JsonNode retainedRules = fixture.retainedRules();
        fixture.service.deleteSecurityGroup(REGION, fixture.group);
        fixture.assertGroupAndRulesAbsent();
        AwsException error = assertThrows(AwsException.class, fixture::modify);
        assertEquals("InvalidGroup.NotFound", error.getErrorCode());
        fixture.assertGroupAndRulesAbsent();
        assertEquals(retainedRules, fixture.retainedRules());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void capturedDescriptionUpdateCannotRestoreARevokedRule(boolean egress) throws Exception {
        Fixture fixture = new Fixture(egress, directory);
        fixture.rules.parkNextRead();
        assertOperationWaitsForParkedStore(fixture.rules, fixture::modify, () ->
                fixture.service.revokeSecurityGroupRulesByIds(REGION, fixture.group,
                        List.of(fixture.rule.getSecurityGroupRuleId()), egress));
        assertTrue(fixture.service.describeSecurityGroupRules(REGION, List.of(fixture.group),
                List.of(fixture.rule.getSecurityGroupRuleId())).isEmpty());
        assertTrue((egress ? fixture.groupValue().getIpPermissionsEgress() : fixture.groupValue().getIpPermissions())
                .stream().noneMatch(permission -> "tcp".equals(permission.getIpProtocol())));
    }

    @ParameterizedTest
    @CsvSource({
        "false, ipRanges", "true, ipRanges",
        "false, ipv6Ranges", "true, ipv6Ranges",
        "false, prefixListIds", "true, prefixListIds",
        "false, userIdGroupPairs", "true, userIdGroupPairs"
    })
    void onlyTheSelectedSourceDescriptionChangesAndSurvivesReload(boolean egress, String source) {
        Fixture fixture = new Fixture(egress, directory);
        IpPermission permission = fixture.mixedSourcePermission();
        List<SecurityGroupRule> added = egress
                ? fixture.service.authorizeSecurityGroupEgress(REGION, fixture.group, List.of(permission))
                : fixture.service.authorizeSecurityGroupIngress(REGION, fixture.group, List.of(permission));
        SecurityGroupRule selected = added.stream().filter(rule -> switch (source) {
            case "ipRanges" -> rule.getCidrIpv4() != null;
            case "ipv6Ranges" -> rule.getCidrIpv6() != null;
            case "prefixListIds" -> rule.getPrefixListId() != null;
            case "userIdGroupPairs" -> rule.getReferencedGroupInfo() != null;
            default -> throw new IllegalArgumentException(source);
        }).findFirst().orElseThrow();
        JsonNode expectedGroup = MAPPER.valueToTree(fixture.groupValue());
        for (JsonNode expectedPermission : expectedGroup.get(egress ? "ipPermissionsEgress" : "ipPermissions")) {
            if (expectedPermission.path("fromPort").asInt() == 8443) {
                ((ObjectNode) expectedPermission.get(source).get(0)).put("description", "after");
            }
        }
        List<String> retainedIds = added.stream().filter(rule -> rule != selected)
                .map(SecurityGroupRule::getSecurityGroupRuleId).toList();
        JsonNode retainedRules = MAPPER.valueToTree(fixture.service.describeSecurityGroupRules(
                REGION, List.of(fixture.group), retainedIds));

        fixture.service.modifySecurityGroupRules(REGION, fixture.group, List.of(
                Map.of("SecurityGroupRuleId", selected.getSecurityGroupRuleId(), "Description", "after")));

        assertEquals("after", fixture.service.describeSecurityGroupRules(REGION, List.of(fixture.group),
                List.of(selected.getSecurityGroupRuleId())).getFirst().getDescription());
        assertEquals(retainedRules, MAPPER.valueToTree(fixture.service.describeSecurityGroupRules(
                REGION, List.of(fixture.group), retainedIds)));
        assertEquals(expectedGroup, MAPPER.valueToTree(fixture.groupValue()));
        fixture.groups.load();
        assertEquals(expectedGroup, MAPPER.valueToTree(fixture.groupValue()));
    }

    private static final class Fixture {
        private final ParkingStore<SecurityGroupRule> rules = new ParkingStore<>();
        private final PersistentStorage<String, SecurityGroup> groups;
        private final Ec2Service service;
        private final String group;
        private final String retainedGroup;
        private final SecurityGroupRule rule;

        private Fixture(boolean egress, Path directory) {
            groups = new PersistentStorage<>(directory.resolve("groups.json"), new TypeReference<>() {});
            InMemoryStorageFactory stores = new InMemoryStorageFactory("000000000000")
                    .withStore("ec2-security-group-rules.json", rules)
                    .withStore("ec2-security-groups.json", groups);
            EmulatorConfig config = mock(EmulatorConfig.class);
            EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
            EmulatorConfig.Ec2ServiceConfig ec2 = mock(EmulatorConfig.Ec2ServiceConfig.class);
            when(config.defaultAccountId()).thenReturn("000000000000");
            when(config.services()).thenReturn(services);
            when(services.ec2()).thenReturn(ec2);
            when(ec2.mock()).thenReturn(true);
            when(ec2.mockPublicAddresses()).thenReturn(EmulatorConfig.Ec2ServiceConfig.MockPublicAddresses.NONE);
            service = new Ec2Service(config, mock(Ec2ContainerManager.class), mock(Ec2PortForwardManager.class),
                    mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(), stores);
            String vpc = service.createVpc(REGION, "10.66.0.0/16", false).getVpcId();
            group = service.createSecurityGroup(REGION, "owned", "owned", vpc).getGroupId();
            retainedGroup = service.createSecurityGroup(REGION, "retained", "retained", vpc).getGroupId();
            IpPermission permission = new IpPermission();
            permission.setIpProtocol("tcp");
            permission.setFromPort(443);
            permission.setToPort(443);
            permission.setIpRanges(new ArrayList<>(List.of(new IpRange("10.66.0.0/16", "before"))));
            List<SecurityGroupRule> added = egress
                    ? service.authorizeSecurityGroupEgress(REGION, group, List.of(permission))
                    : service.authorizeSecurityGroupIngress(REGION, group, List.of(permission));
            rule = added.getFirst();
        }

        private IpPermission mixedSourcePermission() {
            String prefixList = service.createManagedPrefixList(REGION, "description-source", "IPv4",
                    1, List.of(), List.of()).getPrefixListId();
            UserIdGroupPair pair = new UserIdGroupPair();
            pair.setGroupId(retainedGroup);
            pair.setDescription("before");
            IpPermission permission = new IpPermission();
            permission.setIpProtocol("tcp");
            permission.setFromPort(8443);
            permission.setToPort(8443);
            permission.setIpRanges(List.of(new IpRange("10.66.1.0/24", "before")));
            permission.setIpv6Ranges(List.of(new Ipv6Range("2001:db8::/64", "before")));
            permission.setPrefixListIds(List.of(new PrefixListId(prefixList, "before")));
            permission.setUserIdGroupPairs(List.of(pair));
            return permission;
        }

        private SecurityGroup groupValue() {
            return service.describeSecurityGroups(REGION, List.of(group), List.of(), Map.of()).getFirst();
        }

        private void modify() {
            service.modifySecurityGroupRules(REGION, group, List.of(
                    Map.of("SecurityGroupRuleId", rule.getSecurityGroupRuleId(), "Description", "after")));
        }

        private JsonNode retainedRules() {
            return MAPPER.valueToTree(service.describeSecurityGroupRules(REGION, List.of(retainedGroup), List.of()));
        }

        private void assertGroupAndRulesAbsent() {
            assertTrue(service.describeSecurityGroups(REGION, List.of(), List.of(), Map.of()).stream()
                    .noneMatch(candidate -> candidate.getGroupId().equals(group)), "Deleted group stays absent");
            assertTrue(service.describeSecurityGroupRules(REGION, List.of(group), List.of()).isEmpty(),
                    "Description update must not restore a rule whose group was deleted");
        }
    }
}
