package io.floci.aws.services.ec2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.services.ec2.model.IpPermission;
import io.floci.aws.services.ec2.model.IpRange;
import io.floci.aws.services.ec2.model.SecurityGroupRule;
import io.floci.aws.services.ec2.portforward.Ec2PortForwardManager;
import io.floci.aws.testing.InMemoryStorageFactory;
import io.floci.aws.testing.ParkingStore;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static io.floci.aws.testing.ParkingStoreAssertions.assertOperationWaitsForParkedStore;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class Ec2RuleDescriptionPublicationServiceTest {
    private static final String REGION = "us-east-1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void capturedDescriptionUpdateCannotRestoreARuleAfterGroupDeletion(boolean egress) throws Exception {
        Fixture fixture = new Fixture(egress);
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
        Fixture fixture = new Fixture(egress);
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
        Fixture fixture = new Fixture(egress);
        JsonNode retainedRules = fixture.retainedRules();
        fixture.service.deleteSecurityGroup(REGION, fixture.group);
        fixture.assertGroupAndRulesAbsent();
        fixture.modify();
        fixture.assertGroupAndRulesAbsent();
        assertEquals(retainedRules, fixture.retainedRules());
    }

    private static final class Fixture {
        private final ParkingStore<SecurityGroupRule> rules = new ParkingStore<>();
        private final Ec2Service service;
        private final String group;
        private final String retainedGroup;
        private final SecurityGroupRule rule;

        private Fixture(boolean egress) {
            InMemoryStorageFactory stores = new InMemoryStorageFactory("000000000000")
                    .withStore("ec2-security-group-rules.json", rules);
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
            permission.setIpRanges(List.of(new IpRange("10.66.0.0/16", "before")));
            List<SecurityGroupRule> added = egress
                    ? service.authorizeSecurityGroupEgress(REGION, group, List.of(permission))
                    : service.authorizeSecurityGroupIngress(REGION, group, List.of(permission));
            rule = added.getFirst();
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
