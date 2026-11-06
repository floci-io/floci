package io.floci.aws.services.dsql;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.dsql.model.DsqlCluster;
import io.floci.aws.services.iam.IamPolicyEvaluator;
import io.floci.aws.services.iam.IamService.CallerArns;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The control plane in isolation, with the answers measured on AWS (eu-west-1, 2026-10-20):
 * policy versions and refusals, the lockout check, tag calls on what is no cluster of the Region,
 * multi-Region peering, and updates that leave out a member.
 */
class DsqlServiceTest {

    private static final String ACCOUNT = "111122223333";
    private static final String REGION = "eu-west-1";
    private static final String ROOT = "arn:aws:iam::" + ACCOUNT + ":root";

    private final DsqlPrincipals principals = mock(DsqlPrincipals.class);
    private final DsqlDataPlane dataPlane = mock(DsqlDataPlane.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final DsqlService service = new DsqlService(
            new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, ACCOUNT),
            new RegionResolver(REGION, ACCOUNT), "localhost.floci.io", dataPlane, principals,
            new IamPolicyEvaluator(mapper), mapper, true);

    @BeforeEach
    void caller() {
        // A database that is ready at once and gone at once, as with floci.services.dsql.mock.
        readyAtOnce(dataPlane);
        doAnswer(call -> {
            call.<Runnable>getArgument(1).run();
            return null;
        }).when(dataPlane).deprovision(any(), any());
        when(principals.caller()).thenReturn(CallerArns.accountRoot(ROOT, ACCOUNT));
        when(principals.exists(anyString())).thenAnswer(call -> call.getArgument(0, String.class).endsWith(":root"));
    }

    @Test
    void aPolicyVersionIsWhenThePolicyWasSet() {
        String id = cluster().getIdentifier();
        long before = Instant.now().toEpochMilli();

        String first = service.putClusterPolicy(REGION, id, deny("dsql:DbConnect"), null, false);
        String second = service.putClusterPolicy(REGION, id, deny("dsql:DbConnect"), first, false);

        assertTrue(Long.parseLong(first) >= before);
        assertTrue(Long.parseLong(second) > Long.parseLong(first));
        assertEquals(second, service.getClusterPolicy(REGION, id).getPolicyVersion());
        assertEquals(second, service.deleteClusterPolicy(REGION, id, null));
        AwsException none = assertThrows(AwsException.class, () -> service.getClusterPolicy(REGION, id));
        assertEquals("ResourceNotFoundException", none.getErrorCode());
        assertEquals("Aurora DSQL can't find the policy for the resource " + id + ". Verify that the policy exists.",
                none.getMessage());
        assertEquals(Map.of("resourceId", id, "resourceType", "policy"), none.getExtendedData());
    }

    @Test
    void aVersionThatIsNotTheCurrentPolicysIsRefused() {
        String id = cluster().getIdentifier();
        String noVersion = "Aurora DSQL can't find policy version 1 for resource " + id
                + ". Verify that the policy exists and that the version is correct.";

        AwsException noPolicy = assertThrows(AwsException.class,
                () -> service.putClusterPolicy(REGION, id, deny("dsql:DbConnect"), "1", false));
        assertEquals("ResourceNotFoundException", noPolicy.getErrorCode());
        assertEquals(noVersion, noPolicy.getMessage());
        service.putClusterPolicy(REGION, id, deny("dsql:DbConnect"), null, false);
        AwsException conflict = assertThrows(AwsException.class,
                () -> service.putClusterPolicy(REGION, id, deny("dsql:DbConnect"), "1", false));
        assertEquals("ConflictException", conflict.getErrorCode());
        assertEquals(noVersion, conflict.getMessage());
        assertEquals("cluster", conflict.getExtendedData().get("resourceType"));
        AwsException delete = assertThrows(AwsException.class, () -> service.deleteClusterPolicy(REGION, id, "1"));
        assertEquals("ResourceNotFoundException", delete.getErrorCode());
        assertEquals("policy", delete.getExtendedData().get("resourceType"));

        String current = service.getClusterPolicy(REGION, id).getPolicyVersion();
        service.deleteClusterPolicy(REGION, id, current);
        AwsException deleted = assertThrows(AwsException.class,
                () -> service.putClusterPolicy(REGION, id, deny("dsql:DbConnect"), current, false));
        assertEquals("ResourceNotFoundException", deleted.getErrorCode());
        assertEquals("Aurora DSQL can't find policy version " + current + " for resource " + id
                + ". Verify that the policy exists and that the version is correct.", deleted.getMessage());
    }

    @Test
    void aPolicyThatLocksTheCallerOutOfBothPolicyActionsIsRefused() {
        String id = cluster().getIdentifier();

        AwsException lockout = assertThrows(AwsException.class, () -> service.putClusterPolicy(REGION, id,
                deny("[\"dsql:PutClusterPolicy\",\"dsql:DeleteClusterPolicy\"]"), null, false));
        assertEquals(DsqlService.LOCKOUT, lockout.getMessage());
        service.putClusterPolicy(REGION, id, deny("\"dsql:PutClusterPolicy\""), null, false);
        service.putClusterPolicy(REGION, id, deny("\"*\""), null, true);
        AwsException onCreate = assertThrows(AwsException.class, () -> service.createCluster(REGION, false, null,
                null, null, null, null, deny("\"*\""), false));
        assertEquals(DsqlService.LOCKOUT, onCreate.getMessage());
    }

    @Test
    void aPublicPolicyIsRefused() {
        AwsException refused = assertThrows(AwsException.class, () -> service.createCluster(REGION, false, null, null,
                null, null, null, "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":\"*\","
                        + "\"Action\":\"dsql:DbConnect\",\"Resource\":\"*\"}]}", false));

        assertEquals(DsqlClusterPolicies.PUBLIC, refused.getMessage());
        assertEquals(0, service.listClusters(REGION, null, null).clusters().size());
    }

    @Test
    void aTagCallRefusesWhatIsNoClusterOfTheRegion() {
        DsqlCluster cluster = cluster();
        String missing = "arn:aws:dsql:" + REGION + ":" + ACCOUNT + ":cluster/aaaaaaaaaaaaaaaaaaaaaaaaaa";

        AwsException invalid = assertThrows(AwsException.class,
                () -> service.listTags(REGION, "arn:aws:dsql:" + REGION + ":" + ACCOUNT + ":cluster/abc"));
        assertEquals("AccessDeniedException", invalid.getErrorCode());
        assertEquals(403, invalid.getHttpStatus());
        assertEquals("Invalid input for 'resourceArn'. The cluster ARN that you specified isn't valid. Provide the"
                + " cluster ARN in the format 'arn:partition:dsql:region:account-id:cluster/cluster-id' and try"
                + " again.User: " + ROOT + " is not authorized to perform: dsql:ListTagsForResource on resource: *"
                + " with an explicit deny in a resource-based policy", invalid.getMessage());
        AwsException otherRegion = assertThrows(AwsException.class,
                () -> service.tagResource("us-east-1", cluster.getArn(), Map.of("k", "v")));
        assertEquals("BadRequestException", otherRegion.getErrorCode());
        assertEquals("Invalid input resource arn: expected region us-east-1", otherRegion.getMessage());
        AwsException notFound = assertThrows(AwsException.class,
                () -> service.untagResource(REGION, missing, List.of("k")));
        assertEquals("NotFoundException", notFound.getErrorCode());
        assertEquals("Resource was not found", notFound.getMessage());
    }

    @Test
    void multiRegionClustersWaitUntilEachNamesTheOther() {
        DsqlCluster first = service.createCluster(REGION, false, null, null, null, "eu-west-2", null, null, false);
        DsqlCluster second = service.createCluster("eu-west-3", false, null, null, null, "eu-west-2", null, null, false);
        assertEquals(DsqlService.PENDING_SETUP, first.getStatus());
        assertEquals(List.of(first.getArn()), first.getLinkedClusterArns());

        service.updateCluster(REGION, first.getIdentifier(), null, null, null, List.of(second.getArn()));
        assertEquals(List.of(second.getArn(), first.getArn()),
                service.getCluster(REGION, first.getIdentifier()).getLinkedClusterArns());
        assertEquals(DsqlService.PENDING_SETUP, service.getCluster(REGION, first.getIdentifier()).getStatus());

        service.updateCluster("eu-west-3", second.getIdentifier(), null, null, null, List.of(first.getArn()));
        assertEquals(DsqlService.ACTIVE, service.getCluster(REGION, first.getIdentifier()).getStatus());
        assertEquals(DsqlService.ACTIVE, service.getCluster("eu-west-3", second.getIdentifier()).getStatus());
        assertEquals(List.of(first.getArn(), second.getArn()),
                service.getCluster("eu-west-3", second.getIdentifier()).getLinkedClusterArns());
    }

    /**
     * Peering makes a cluster ACTIVE only once its database answers: until then it is CREATING, and
     * one whose database failed is FAILED, not left waiting.
     */
    @Test
    void aPeerIsActiveOnlyOnceItsDatabaseAnswers() {
        DsqlDataPlane slow = mock(DsqlDataPlane.class);
        Map<String, Consumer<Boolean>> starting = new HashMap<>();
        doAnswer(call -> {
            starting.put(call.<DsqlCluster>getArgument(0).getIdentifier(), call.getArgument(1));
            return null;
        }).when(slow).provision(any(), any());
        DsqlService peers = service(new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, ACCOUNT), slow);
        DsqlCluster first = peers.createCluster(REGION, false, null, null, null, "eu-west-2", null, null, false);
        DsqlCluster second = peers.createCluster("eu-west-3", false, null, null, null, "eu-west-2", null, null, false);
        DsqlCluster failing = peers.createCluster(REGION, false, null, null, null, "eu-west-2", null, null, false);

        starting.get(first.getIdentifier()).accept(true);
        starting.get(failing.getIdentifier()).accept(false);
        assertEquals(DsqlService.PENDING_SETUP, peers.getCluster(REGION, first.getIdentifier()).getStatus());
        assertEquals(DsqlService.FAILED, peers.getCluster(REGION, failing.getIdentifier()).getStatus());

        peers.updateCluster(REGION, first.getIdentifier(), null, null, null, List.of(second.getArn()));
        peers.updateCluster("eu-west-3", second.getIdentifier(), null, null, null, List.of(first.getArn()));
        assertEquals(DsqlService.ACTIVE, peers.getCluster(REGION, first.getIdentifier()).getStatus());
        assertEquals(DsqlService.CREATING, peers.getCluster("eu-west-3", second.getIdentifier()).getStatus());

        starting.get(second.getIdentifier()).accept(true);
        assertEquals(DsqlService.ACTIVE, peers.getCluster("eu-west-3", second.getIdentifier()).getStatus());
    }

    @Test
    void anUpdateLeavesWhatItDoesNotNameAsItWas() {
        DsqlCluster cluster = service.createCluster(REGION, false, "arn:aws:kms:eu-west-1:" + ACCOUNT + ":key/k",
                null, null, "eu-west-2", null, null, false);

        service.updateCluster(REGION, cluster.getIdentifier(), null, null, null, null);

        DsqlCluster updated = service.getCluster(REGION, cluster.getIdentifier());
        assertEquals(false, updated.isDeletionProtectionEnabled());
        assertEquals("arn:aws:kms:eu-west-1:" + ACCOUNT + ":key/k", updated.getKmsEncryptionKey());
        assertEquals("eu-west-2", updated.getWitnessRegion());
        assertEquals(List.of(cluster.getArn()), updated.getLinkedClusterArns());
    }

    @Test
    void aCloudFormationUpdateKeepsAPolicyItAlreadyHas() {
        String id = cluster().getIdentifier();
        String version = service.putClusterPolicy(REGION, id, deny("\"dsql:DbConnect\""), null, false);

        service.replaceClusterPolicy(REGION, id, deny("[\"dsql:DbConnect\"]"));
        assertEquals(version, service.getCluster(REGION, id).getPolicyVersion());
        service.replaceClusterPolicy(REGION, id, null);
        assertNull(service.getCluster(REGION, id).getPolicy());
    }

    @Test
    void listingRefusesWhatItCannotPage() {
        AwsException token = assertThrows(AwsException.class, () -> service.listClusters(REGION, null, "garbage"));
        assertEquals("Invalid pagination token. Request a new token and try again.", token.getMessage());
        assertEquals(Map.of("reason", "fieldValidationFailed", "fieldList", List.of(Map.of("name", "next-token",
                "message", "Invalid pagination token. Request a new token and try again."))), token.getExtendedData());
        AwsException tooMany = assertThrows(AwsException.class, () -> service.listClusters(REGION, 101, null));
        assertEquals("1 validation error detected: Value at 'maxResults' failed to satisfy constraint: Member must"
                + " have value less than or equal to 100", tooMany.getMessage());
        assertNull(tooMany.getExtendedData());
    }

    /**
     * A restart over the same storage starts every cluster's database again, and finishes a
     * deletion the stop interrupted.
     */
    @Test
    void aRestartBringsEveryClusterBackAndFinishesAnInterruptedDeletion() {
        AccountAwareStorageBackend<DsqlCluster> storage =
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, ACCOUNT);
        DsqlDataPlane stopping = mock(DsqlDataPlane.class);
        readyAtOnce(stopping);
        DsqlService before = service(storage, stopping);
        DsqlCluster kept = before.createCluster(REGION, false, null, null, null, null, null, null, false);
        DsqlCluster deleting = before.createCluster(REGION, false, null, null, null, null, null, null, false);
        // The stop comes before the database is gone, so the cluster stays DELETING.
        before.deleteCluster(REGION, deleting.getIdentifier());

        DsqlService after = service(storage, dataPlane);
        after.onStart(null);

        verify(dataPlane).provision(argThat(cluster -> kept.getIdentifier().equals(cluster.getIdentifier())), any());
        verify(dataPlane).deprovision(argThat(cluster -> deleting.getIdentifier().equals(cluster.getIdentifier())), any());
        assertEquals(DsqlService.ACTIVE, after.getCluster(REGION, kept.getIdentifier()).getStatus());
        assertThrows(AwsException.class, () -> after.getCluster(REGION, deleting.getIdentifier()));
    }

    /** Makes {@code plane} start every cluster's database at once. */
    private static void readyAtOnce(DsqlDataPlane plane) {
        doAnswer(call -> {
            call.<Consumer<Boolean>>getArgument(1).accept(true);
            return null;
        }).when(plane).provision(any(), any());
    }

    private DsqlService service(AccountAwareStorageBackend<DsqlCluster> storage, DsqlDataPlane plane) {
        return new DsqlService(storage, new RegionResolver(REGION, ACCOUNT), "localhost.floci.io", plane, principals,
                new IamPolicyEvaluator(mapper), mapper, true);
    }

    private DsqlCluster cluster() {
        return service.createCluster(REGION, false, null, null, null, null, null, null, false);
    }

    /** A Deny to everyone outside one VPC, which the caller is not in. */
    private static String deny(String action) {
        String actions = action.startsWith("[") || action.startsWith("\"") ? action : "\"" + action + "\"";
        return "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Deny\",\"Principal\":{\"AWS\":\"*\"},"
                + "\"Action\":" + actions + ",\"Resource\":\"*\",\"Condition\":{\"StringNotEquals\":"
                + "{\"aws:SourceVpc\":\"vpc-0123456789abcdef0\"}}}]}";
    }
}
