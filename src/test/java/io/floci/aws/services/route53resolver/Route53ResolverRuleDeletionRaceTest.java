package io.floci.aws.services.route53resolver;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.testing.ParkingStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.floci.aws.testing.ParkingStoreAssertions.assertOperationWaitsForParkedStore;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A resolver rule's deletion checks that no VPC is associated with it, so an association being made at the same time
 * must be stored before that check reads the associations, or after the rule is gone and refuses it: never in
 * between, where it would outlive its rule.
 */
class Route53ResolverRuleDeletionRaceTest {

    private static final String REGION = "eu-west-1";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void aDeletionWaitsForAnAssociationBeingStoredAndThenRefusesTheRule() throws Exception {
        ParkingStore<ObjectNode> associations = new ParkingStore<>();
        Route53ResolverService service = new Route53ResolverService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), associations, new InMemoryStorage<>(), new InMemoryStorage<>(), objectMapper);
        String ruleId = service.createResolverRule(objectMapper.createObjectNode().put("RuleType", "SYSTEM")
                .put("DomainName", "example.com"), REGION, "000000000000").get("Id").asText();
        associations.parkNextWrite();

        assertOperationWaitsForParkedStore(associations,
                () -> service.associateResolverRule(objectMapper.createObjectNode().put("ResolverRuleId", ruleId)
                        .put("VPCId", "vpc-1"), REGION),
                () -> assertEquals("ResourceInUseException", assertThrows(AwsException.class,
                        () -> service.deleteResolverRule(ruleId, REGION)).getErrorCode()));

        assertEquals(ruleId, service.getResolverRule(ruleId, REGION).get("Id").asText());
        assertEquals(List.of(ruleId), service.listResolverRuleAssociations(REGION).stream()
                .map(association -> association.get("ResolverRuleId").asText()).toList());
    }
}
