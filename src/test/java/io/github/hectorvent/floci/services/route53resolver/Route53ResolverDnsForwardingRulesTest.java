package io.github.hectorvent.floci.services.route53resolver;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.dns.DnsForwardingRule;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class Route53ResolverDnsForwardingRulesTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    @Inject
    Route53ResolverService resolverService;

    @Inject
    Route53ResolverDnsForwardingRules forwardingRules;

    @Inject
    ObjectMapper objectMapper;

    private final List<String> createdRuleIds = new ArrayList<>();

    @AfterEach
    void deleteCreatedRules() {
        for (String ruleId : createdRuleIds) {
            try {
                resolverService.deleteResolverRule(ruleId);
            } catch (Exception ignored) {
                // best-effort cleanup; the rule may already be gone
            }
        }
        createdRuleIds.clear();
    }

    @Test
    void anAssociatedForwardRuleCarriesItsTargetsAndTheirPorts() {
        String vpcId = uniqueVpcId();
        associate(forwardRule("corp.internal", target("10.1.0.53", null), target("10.1.0.54", 5353)), vpcId);

        List<DnsForwardingRule> rules = forwardingRules.rulesFor(ACCOUNT, REGION, vpcId);

        assertEquals(1, rules.size());
        assertEquals("corp.internal", rules.getFirst().domainName());
        assertEquals(List.of(new DnsForwardingRule.Target("10.1.0.53", 53),
                new DnsForwardingRule.Target("10.1.0.54", 5353)), rules.getFirst().targets());
    }

    @Test
    void aRuleAppliesToNoVpcUntilItIsAssociatedWithOne() {
        createRule(forwardRule("unassociated.internal", target("10.1.0.53", null)));
        associate(forwardRule("elsewhere.internal", target("10.1.0.53", null)), uniqueVpcId());

        assertTrue(forwardingRules.rulesFor(ACCOUNT, REGION, uniqueVpcId()).isEmpty());
    }

    @Test
    void anotherAccountSeesNoneOfThisAccountsAssociations() {
        // AssociateResolverRule stores whatever VPC id the caller names, so a VPC id is not proof of
        // ownership. Only the account whose partition holds the association may be steered by it,
        // otherwise any account could redirect another account's cluster DNS by naming its VPC.
        String vpcId = uniqueVpcId();
        associate(forwardRule("corp.internal", target("10.1.0.53", null)), vpcId);
        assertEquals(1, forwardingRules.rulesFor(ACCOUNT, REGION, vpcId).size());

        assertTrue(forwardingRules.rulesFor("999999999999", REGION, vpcId).isEmpty());
        assertTrue(resolverService.resolverRuleIdsAssociatedWith("999999999999", vpcId).isEmpty());
    }

    @Test
    void noAccountRegionOrVpcMeansNoRules() {
        String vpcId = uniqueVpcId();
        associate(forwardRule("corp.internal", target("10.1.0.53", null)), vpcId);

        assertTrue(forwardingRules.rulesFor(null, REGION, vpcId).isEmpty());
        assertTrue(forwardingRules.rulesFor(ACCOUNT, "  ", vpcId).isEmpty());
        assertTrue(forwardingRules.rulesFor(ACCOUNT, REGION, "  ").isEmpty());
    }

    @Test
    void aRuleFromAnotherRegionDoesNotSteerThisRegionsQueries() {
        // Route 53 Resolver is regional: "objects that you create in one AWS Region are available
        // only in that Region". The stores carry no region in their keys, so the rule's region comes
        // from the ARN this service minted for it.
        String vpcId = uniqueVpcId();
        String ruleId = createRuleIn(forwardRule("corp.internal", target("10.1.0.53", null)), "eu-west-1");
        ObjectNode association = objectMapper.createObjectNode();
        association.put("ResolverRuleId", ruleId);
        association.put("VPCId", vpcId);
        resolverService.associateResolverRule(association);

        assertEquals(List.of(ruleId), resolverService.resolverRuleIdsAssociatedWith(ACCOUNT, vpcId));
        assertTrue(forwardingRules.rulesFor(ACCOUNT, REGION, vpcId).isEmpty());
        assertEquals(1, forwardingRules.rulesFor(ACCOUNT, "eu-west-1", vpcId).size());
    }

    @Test
    void aSystemRuleCarriesNoTargetsAndSortsAheadOfTheBroaderForwardRule() {
        String vpcId = uniqueVpcId();
        associate(forwardRule("corp.internal", target("10.1.0.53", null)), vpcId);
        associate(systemRule("acme.corp.internal"), vpcId);

        List<DnsForwardingRule> rules = forwardingRules.rulesFor(ACCOUNT, REGION, vpcId);

        assertEquals(List.of("acme.corp.internal", "corp.internal"),
                rules.stream().map(DnsForwardingRule::domainName).toList());
        assertTrue(rules.getFirst().targets().isEmpty());
        assertTrue(rules.getLast().forwards());
    }

    @Test
    void aRecursiveRuleResolvesLocallyAndADelegateRuleIsNotApplied() {
        String vpcId = uniqueVpcId();
        associate(ruleRequest("RECURSIVE", "recursive.internal"), vpcId);
        associate(ruleRequest("DELEGATE", "delegate.internal"), vpcId);

        List<DnsForwardingRule> rules = forwardingRules.rulesFor(ACCOUNT, REGION, vpcId);

        assertEquals(List.of("recursive.internal"),
                rules.stream().map(DnsForwardingRule::domainName).toList());
        assertTrue(rules.getFirst().targets().isEmpty());
    }

    @Test
    void aForwardRuleWithOnlyIpv6TargetsIsNotApplied() {
        String vpcId = uniqueVpcId();
        ObjectNode request = ruleRequest("FORWARD", "corp.internal");
        ObjectNode ipv6Target = objectMapper.createObjectNode();
        ipv6Target.put("Ipv6", "2001:db8::53");
        request.putArray("TargetIps").add(ipv6Target);
        associate(request, vpcId);

        assertTrue(forwardingRules.rulesFor(ACCOUNT, REGION, vpcId).isEmpty());
    }

    private void associate(ObjectNode ruleRequest, String vpcId) {
        ObjectNode association = objectMapper.createObjectNode();
        association.put("ResolverRuleId", createRule(ruleRequest));
        association.put("VPCId", vpcId);
        resolverService.associateResolverRule(association);
    }

    private String createRule(ObjectNode ruleRequest) {
        return createRuleIn(ruleRequest, REGION);
    }

    private String createRuleIn(ObjectNode ruleRequest, String region) {
        String ruleId = resolverService.createResolverRule(ruleRequest, region, ACCOUNT).get("Id").asText();
        createdRuleIds.add(ruleId);
        return ruleId;
    }

    private ObjectNode forwardRule(String domainName, ObjectNode... targets) {
        ObjectNode request = ruleRequest("FORWARD", domainName);
        for (ObjectNode target : targets) {
            request.withArray("TargetIps").add(target);
        }
        return request;
    }

    private ObjectNode systemRule(String domainName) {
        return ruleRequest("SYSTEM", domainName);
    }

    private ObjectNode ruleRequest(String ruleType, String domainName) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("Name", ruleType.toLowerCase() + "-rule");
        request.put("RuleType", ruleType);
        request.put("DomainName", domainName);
        return request;
    }

    private ObjectNode target(String ip, Integer port) {
        ObjectNode target = objectMapper.createObjectNode();
        target.put("Ip", ip);
        if (port != null) {
            target.put("Port", port);
        }
        return target;
    }

    /** A VPC id per test, so rules from other tests in the same store cannot leak into the result. */
    private static String uniqueVpcId() {
        return "vpc-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
