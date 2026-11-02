package io.github.hectorvent.floci.services.ssm;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetResourcePoliciesResponse;
import software.amazon.awssdk.services.ssm.model.GetResourcePoliciesResponseEntry;
import software.amazon.awssdk.services.ssm.model.MalformedResourcePolicyDocumentException;
import software.amazon.awssdk.services.ssm.model.ParameterTier;
import software.amazon.awssdk.services.ssm.model.ParameterType;
import software.amazon.awssdk.services.ssm.model.PutResourcePolicyResponse;
import software.amazon.awssdk.services.ssm.model.ResourceNotFoundException;
import software.amazon.awssdk.services.ssm.model.ResourcePolicyConflictException;
import software.amazon.awssdk.services.ssm.model.ResourcePolicyInvalidParameterException;
import software.amazon.awssdk.services.ssm.model.ResourcePolicyLimitExceededException;
import software.amazon.awssdk.services.ssm.model.ResourcePolicyNotFoundException;
import software.amazon.awssdk.services.ssm.model.SsmException;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class SsmResourcePolicyIntegrationTest {

    private static final String POLICY = """
            {"Version":"2012-10-17","Statement":[{"Sid":"Share","Effect":"Allow",\
            "Principal":{"AWS":"arn:aws:iam::111122223333:root"},\
            "Action":["ssm:GetParameter"],"Resource":"*"}]}""";
    private static final String UPDATED_POLICY = POLICY.replace("\"Share\"", "\"ShareUpdated\"");

    private SsmClient ssm;

    @BeforeEach
    void createClient() {
        ssm = AwsTestClients.create(SsmClient.builder(), "000000000000", Region.US_EAST_1);
    }

    @AfterEach
    void closeClient() {
        ssm.close();
    }

    @Test
    void putGetUpdateAndDeleteTheParameterPolicy() {
        String arn = parameter(ParameterTier.ADVANCED);
        assertTrue(policies(arn).isEmpty());

        PutResourcePolicyResponse created = ssm.putResourcePolicy(r -> r.resourceArn(arn).policy(POLICY));
        assertFalse(created.policyId().isEmpty());
        assertFalse(created.policyHash().isEmpty());
        List<GetResourcePoliciesResponseEntry> attached = policies(arn);
        assertEquals(1, attached.size());
        assertEquals(created.policyId(), attached.get(0).policyId());
        assertEquals(created.policyHash(), attached.get(0).policyHash());
        assertEquals(POLICY, attached.get(0).policy());

        PutResourcePolicyResponse updated = ssm.putResourcePolicy(r -> r.resourceArn(arn).policy(UPDATED_POLICY)
                .policyId(created.policyId()).policyHash(created.policyHash()));
        assertEquals(created.policyId(), updated.policyId());
        assertNotEquals(created.policyHash(), updated.policyHash());
        assertEquals(UPDATED_POLICY, policies(arn).get(0).policy());
        assertEquals(updated.policyHash(), policies(arn).get(0).policyHash());

        ssm.deleteResourcePolicy(r -> r.resourceArn(arn)
                .policyId(updated.policyId()).policyHash(updated.policyHash()));
        assertTrue(policies(arn).isEmpty());
    }

    @Test
    void aMissingOrStaleHashConflictsWithTheStoredPolicy() {
        String arn = parameter(ParameterTier.ADVANCED);
        PutResourcePolicyResponse created = ssm.putResourcePolicy(r -> r.resourceArn(arn).policy(POLICY));
        PutResourcePolicyResponse updated = ssm.putResourcePolicy(r -> r.resourceArn(arn).policy(UPDATED_POLICY)
                .policyId(created.policyId()).policyHash(created.policyHash()));

        assertThrows(ResourcePolicyConflictException.class,
                () -> ssm.putResourcePolicy(r -> r.resourceArn(arn).policy(POLICY)));
        assertThrows(ResourcePolicyConflictException.class,
                () -> ssm.putResourcePolicy(r -> r.resourceArn(arn).policy(POLICY)
                        .policyHash(updated.policyHash())));
        assertThrows(ResourcePolicyConflictException.class,
                () -> ssm.putResourcePolicy(r -> r.resourceArn(arn).policy(POLICY)
                        .policyId(created.policyId()).policyHash(created.policyHash())));
        assertThrows(ResourcePolicyConflictException.class,
                () -> ssm.deleteResourcePolicy(r -> r.resourceArn(arn)
                        .policyId(created.policyId()).policyHash(created.policyHash())));
        assertThrows(ResourcePolicyNotFoundException.class,
                () -> ssm.deleteResourcePolicy(r -> r.resourceArn(arn)
                        .policyId(UUID.randomUUID().toString()).policyHash(updated.policyHash())));

        List<GetResourcePoliciesResponseEntry> attached = policies(arn);
        assertEquals(1, attached.size());
        assertEquals(updated.policyHash(), attached.get(0).policyHash());
        assertEquals(UPDATED_POLICY, attached.get(0).policy());
    }

    @Test
    void aPolicyIdIsNotFoundOnAParameterWithoutAPolicy() {
        String arn = parameter(ParameterTier.ADVANCED);

        assertThrows(ResourcePolicyNotFoundException.class,
                () -> ssm.putResourcePolicy(r -> r.resourceArn(arn).policy(POLICY)
                        .policyId(UUID.randomUUID().toString()).policyHash("hash")));
        assertThrows(ResourcePolicyNotFoundException.class,
                () -> ssm.deleteResourcePolicy(r -> r.resourceArn(arn)
                        .policyId(UUID.randomUUID().toString()).policyHash("hash")));
        assertTrue(policies(arn).isEmpty());
    }

    @Test
    void anUnknownParameterIsResourceNotFound() {
        String missing = parameter(ParameterTier.ADVANCED) + "-missing";

        assertThrows(ResourceNotFoundException.class,
                () -> ssm.putResourcePolicy(r -> r.resourceArn(missing).policy(POLICY)));
        assertThrows(ResourceNotFoundException.class,
                () -> ssm.getResourcePolicies(r -> r.resourceArn(missing)));
        assertThrows(ResourceNotFoundException.class,
                () -> ssm.deleteResourcePolicy(r -> r.resourceArn(missing).policyId("id").policyHash("hash")));
    }

    @Test
    void anOverwriteKeepsThePolicyAndADeleteRemovesIt() {
        String name = "/resource-policy/" + UUID.randomUUID();
        String arn = parameter(name, ParameterTier.ADVANCED);
        PutResourcePolicyResponse created = ssm.putResourcePolicy(r -> r.resourceArn(arn).policy(POLICY));

        ssm.putParameter(r -> r.name(name).value("v2").type(ParameterType.STRING).overwrite(true));
        List<GetResourcePoliciesResponseEntry> attached = policies(arn);
        assertEquals(1, attached.size());
        assertEquals(created.policyId(), attached.get(0).policyId());

        ssm.deleteParameter(r -> r.name(name));
        parameter(name, ParameterTier.ADVANCED);
        assertTrue(policies(arn).isEmpty());
    }

    @Test
    void onlyAnAdvancedParameterTakesAPolicy() {
        String arn = parameter(ParameterTier.STANDARD);

        assertThrows(ResourcePolicyInvalidParameterException.class,
                () -> ssm.putResourcePolicy(r -> r.resourceArn(arn).policy(POLICY)));
        assertTrue(policies(arn).isEmpty());
    }

    @Test
    void anOverwriteThatWouldLeaveThePolicyOnAnIneligibleParameterIsRefused() {
        String name = "/resource-policy/" + UUID.randomUUID();
        String arn = parameter(name, ParameterTier.ADVANCED);
        ssm.putResourcePolicy(r -> r.resourceArn(arn).policy(POLICY));

        assertThrows(ResourcePolicyInvalidParameterException.class, () -> ssm.putParameter(r -> r.name(name)
                .value("v2").type(ParameterType.STRING).tier(ParameterTier.STANDARD).overwrite(true)));
        assertThrows(ResourcePolicyInvalidParameterException.class, () -> ssm.putParameter(r -> r.name(name)
                .value("v2").type(ParameterType.SECURE_STRING).overwrite(true)));
        assertEquals("v1", ssm.getParameter(r -> r.name(name)).parameter().value());
        assertEquals(1, policies(arn).size());
    }

    @Test
    void aSecureStringUnderTheDefaultKeyTakesNoPolicy() {
        for (String keyId : new String[] {null, "arn:aws:kms:us-east-1:000000000000:alias/aws/ssm"}) {
            String name = "/resource-policy/" + UUID.randomUUID();
            ssm.putParameter(r -> r.name(name).value("secret").type(ParameterType.SECURE_STRING).keyId(keyId)
                    .tier(ParameterTier.ADVANCED));
            String arn = ssm.getParameter(r -> r.name(name)).parameter().arn();

            assertThrows(ResourcePolicyInvalidParameterException.class,
                    () -> ssm.putResourcePolicy(r -> r.resourceArn(arn).policy(POLICY)));
            assertTrue(policies(arn).isEmpty());
        }
    }

    @Test
    void rejectsAnOversizedOrMalformedPolicy() {
        String arn = parameter(ParameterTier.ADVANCED);
        String oversized = "{\"Version\":\"2012-10-17\",\"Statement\":[],\"Sid\":\"" + "x".repeat(1024) + "\"}";

        assertThrows(ResourcePolicyLimitExceededException.class,
                () -> ssm.putResourcePolicy(r -> r.resourceArn(arn).policy(oversized)));
        assertThrows(MalformedResourcePolicyDocumentException.class,
                () -> ssm.putResourcePolicy(r -> r.resourceArn(arn).policy("not-json")));
        assertThrows(MalformedResourcePolicyDocumentException.class,
                () -> ssm.putResourcePolicy(r -> r.resourceArn(arn).policy("[]")));
        assertThrows(MalformedResourcePolicyDocumentException.class,
                () -> ssm.putResourcePolicy(r -> r.resourceArn(arn).policy(POLICY + " {}")));
        assertTrue(policies(arn).isEmpty());
    }

    @Test
    void getResourcePoliciesValidatesMaxResults() {
        String arn = parameter(ParameterTier.ADVANCED);
        ssm.putResourcePolicy(r -> r.resourceArn(arn).policy(POLICY));

        for (int maxResults : new int[] {0, 51}) {
            SsmException e = assertThrows(SsmException.class,
                    () -> ssm.getResourcePolicies(r -> r.resourceArn(arn).maxResults(maxResults)));
            assertEquals("ValidationException", e.awsErrorDetails().errorCode());
        }
        GetResourcePoliciesResponse page = ssm.getResourcePolicies(r -> r.resourceArn(arn).maxResults(1));
        assertEquals(1, page.policies().size());
        assertNull(page.nextToken());
    }

    private String parameter(ParameterTier tier) {
        return parameter("/resource-policy/" + UUID.randomUUID(), tier);
    }

    private String parameter(String name, ParameterTier tier) {
        ssm.putParameter(r -> r.name(name).value("v1").type(ParameterType.STRING).tier(tier));
        return ssm.getParameter(r -> r.name(name)).parameter().arn();
    }

    private List<GetResourcePoliciesResponseEntry> policies(String arn) {
        return ssm.getResourcePolicies(r -> r.resourceArn(arn)).policies();
    }
}
