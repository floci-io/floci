package io.floci.aws.services.redshiftserverless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.iam.ResourcePolicyProvider.ResourcePolicy;
import io.floci.aws.services.redshiftserverless.model.RedshiftServerlessSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedshiftServerlessResourcePolicyProviderTest {

    private static final String OWNER = "111122223333";
    private static final String REGION = "us-east-1";
    private static final String SNAPSHOT_ARN = "arn:aws:redshift-serverless:" + REGION + ":" + OWNER
            + ":snapshot/shared";
    private static final String WITH_RESOURCE = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
            + "\"Principal\":{\"AWS\":\"444455556666\"},\"Action\":\"redshift-serverless:RestoreFromSnapshot\","
            + "\"Resource\":\"*\"}]}";
    private static final String WITHOUT_RESOURCE = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
            + "\"Principal\":{\"AWS\":\"444455556666\"},\"Action\":\"redshift-serverless:RestoreFromSnapshot\"}]}";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RedshiftServerlessService serverless;
    private RedshiftServerlessResourcePolicyService policies;
    private RedshiftServerlessResourcePolicyProvider provider;
    private RedshiftServerlessSnapshot snapshot;

    @BeforeEach
    void setUp() {
        serverless = mock(RedshiftServerlessService.class);
        policies = mock(RedshiftServerlessResourcePolicyService.class);
        provider = new RedshiftServerlessResourcePolicyProvider(serverless, policies, objectMapper);
        snapshot = new RedshiftServerlessSnapshot();
        snapshot.setSnapshotName("shared");
        snapshot.setSnapshotArn(SNAPSHOT_ARN);
        when(serverless.findSnapshotOfAccount(OWNER, "shared", REGION)).thenReturn(Optional.of(snapshot));
        when(policies.policyOfSnapshot(any(), any(), any())).thenReturn(Optional.empty());
    }

    @Test
    void aSnapshotComesWithItsPolicyAndTheAccountInItsArn() {
        when(policies.policyOfSnapshot(eq(OWNER), eq(snapshot), eq(REGION))).thenReturn(Optional.of(WITH_RESOURCE));

        assertEquals(List.of(new ResourcePolicy(WITH_RESOURCE, OWNER)),
                provider.getResourcePolicies("redshift-serverless", SNAPSHOT_ARN));
    }

    @Test
    void aSnapshotWithNoPolicyStillHasItsOwner() {
        assertEquals(List.of(new ResourcePolicy(null, OWNER)),
                provider.getResourcePolicies("redshift-serverless", SNAPSHOT_ARN));
    }

    @Test
    void aSnapshotThatIsNotThereStillHasItsOwner() {
        String missing = "arn:aws:redshift-serverless:" + REGION + ":" + OWNER + ":snapshot/missing";
        when(serverless.findSnapshotOfAccount(OWNER, "missing", REGION)).thenReturn(Optional.empty());

        assertEquals(List.of(new ResourcePolicy(null, OWNER)),
                provider.getResourcePolicies("redshift-serverless", missing));
    }

    @Test
    void aStatementWithNoResourceIsAttachedToTheSnapshot() throws Exception {
        when(policies.policyOfSnapshot(eq(OWNER), eq(snapshot), eq(REGION))).thenReturn(Optional.of(WITHOUT_RESOURCE));

        String policy = provider.getResourcePolicies("redshift-serverless", SNAPSHOT_ARN).getFirst().policyDocument();

        JsonNode statement = objectMapper.readTree(policy).path("Statement").get(0);
        assertEquals(SNAPSHOT_ARN, statement.path("Resource").asText());
        assertEquals("redshift-serverless:RestoreFromSnapshot", statement.path("Action").asText());
    }

    @Test
    void aStatementThatNamesItsResourceIsKept() {
        assertEquals(WITH_RESOURCE, provider.withResource(WITH_RESOURCE, SNAPSHOT_ARN));
        String notResource = WITHOUT_RESOURCE.replace("\"Action\"", "\"NotResource\":\"*\",\"Action\"");
        assertEquals(notResource, provider.withResource(notResource, SNAPSHOT_ARN));
    }

    @Test
    void aSingleStatementObjectIsAttachedToTheSnapshotToo() throws Exception {
        String single = "{\"Version\":\"2012-10-17\",\"Statement\":{\"Effect\":\"Allow\","
                + "\"Principal\":{\"AWS\":\"444455556666\"},\"Action\":\"redshift-serverless:RestoreFromSnapshot\"}}";

        JsonNode statement = objectMapper.readTree(provider.withResource(single, SNAPSHOT_ARN)).path("Statement");

        assertEquals(SNAPSHOT_ARN, statement.path("Resource").asText());
    }

    @Test
    void aPolicyThatCannotBeReadIsPassedOnAsItIs() {
        assertEquals("{not json", provider.withResource("{not json", SNAPSHOT_ARN));
    }

    @Test
    void anythingThatIsNotOneSnapshotIsLeftToOtherProviders() {
        assertTrue(provider.getResourcePolicies("redshift-serverless", "*").isEmpty());
        assertTrue(provider.getResourcePolicies("redshift-serverless",
                "arn:aws:redshift-serverless:" + REGION + ":" + OWNER + ":snapshot/*").isEmpty());
        assertTrue(provider.getResourcePolicies("redshift-serverless",
                "arn:aws:redshift-serverless:" + REGION + ":" + OWNER + ":namespace/abc").isEmpty());
        assertTrue(provider.getResourcePolicies("sqs", "arn:aws:sqs:" + REGION + ":" + OWNER + ":orders").isEmpty());
    }
}
