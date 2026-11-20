package io.floci.aws.services.eks;

import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.autoscaling.AutoScalingService;
import io.floci.aws.services.eks.model.Cluster;
import io.floci.aws.testing.TestAccounts;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.floci.aws.testing.EksRequests.eks;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;

/**
 * EKS changes a node group's Auto Scaling group in the node group's account and Region, also outside a request:
 * when the cluster's node registers, when the startup backfill creates the group, and when a cluster is deleted
 * from Floci's own code. Auto Scaling reads the account and Region from the request scope, so each call is
 * recorded with the ones it was made in.
 */
@QuarkusTest
class EksNodegroupAutoScalingGroupScopeIntegrationTest {

    private static final String REGION = "eu-west-1";

    @InjectMock
    AutoScalingService autoScaling;

    @Inject
    EksService eksService;

    @Inject
    EksClusterManager clusterManager;

    @Inject
    RegionResolver regionResolver;

    /** An Auto Scaling call EKS made, with the account and Region it was made in. */
    private record Call(String operation, String groupName, String accountId, String region) {}

    @Test
    void everyCallToANodeGroupsAutoScalingGroupIsInTheNodeGroupsAccountAndRegion() {
        String account = TestAccounts.randomId();
        String clusterName = "ng-asg-scope-" + UUID.randomUUID().toString().substring(0, 8);
        List<Call> calls = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> record(calls, "create", invocation.getArgument(1)))
                .when(autoScaling).createEksManagedGroup(any(), any(), anyInt(), anyInt(), anyInt(), any(), any());
        doAnswer(invocation -> record(calls, "add", invocation.getArgument(1)))
                .when(autoScaling).addEksManagedInstance(any(), any(), any());
        doAnswer(invocation -> record(calls, "delete", invocation.getArgument(1)))
                .when(autoScaling).deleteEksManagedGroup(any(), any());
        Cluster cluster = null;
        try {
            eks(account, REGION).body(Map.of("name", clusterName, "roleArn", "arn:aws:iam::" + account + ":role/cluster"))
                    .post("/clusters").then().statusCode(200);
            cluster = eksService.findAuthenticationCluster(account, REGION, clusterName).orElseThrow();
            String first = createNodeGroup(account, clusterName, "first");
            String second = createNodeGroup(account, clusterName, "second");
            Set<String> groups = Set.of(first, second);

            assertEquals(List.of(new Call("create", first, account, REGION), new Call("create", second, account, REGION)),
                    callsTo(calls, groups));

            // The node registers outside any request, as it does when its container starts in the background.
            calls.clear();
            clusterManager.registerClusterNodeInstance(cluster, "mock-container-id");

            assertEquals(Set.of(new Call("add", first, account, REGION), new Call("add", second, account, REGION)),
                    Set.copyOf(callsTo(calls, groups)));

            // The startup backfill runs outside any request too, for a node group stored without its group.
            calls.clear();
            RequestScopes.callAs(account, REGION, () -> eksService.describeNodeGroup(clusterName, "first"))
                    .setAutoScalingGroupCreated(false);
            eksService.restoreNodeGroupAutoScalingGroups();

            assertEquals(List.of(new Call("create", first, account, REGION), new Call("add", first, account, REGION)),
                    callsTo(calls, groups));
            assertTrue(RequestScopes.callAs(account, REGION,
                    () -> eksService.describeNodeGroup(clusterName, "first")).isAutoScalingGroupCreated());

            // Floci's own code deletes a cluster outside any request.
            calls.clear();
            eksService.deleteClusterForAccount(account, clusterName);

            assertEquals(Set.of(new Call("delete", first, account, REGION), new Call("delete", second, account, REGION)),
                    Set.copyOf(callsTo(calls, groups)));
        } finally {
            if (eksService.findAuthenticationCluster(account, REGION, clusterName).isPresent()) {
                eksService.deleteClusterForAccount(account, clusterName);
            }
            if (cluster != null) {
                clusterManager.unregisterMetadataEndpoint(cluster);
            }
        }
    }

    private Object record(List<Call> calls, String operation, String groupName) {
        calls.add(new Call(operation, groupName, regionResolver.getAccountId(), regionResolver.getRegion()));
        return null;
    }

    /** The calls to this test's groups: the backfill also visits every other cluster the application holds. */
    private static List<Call> callsTo(List<Call> calls, Set<String> groups) {
        return calls.stream().filter(call -> groups.contains(call.groupName())).toList();
    }

    private static String createNodeGroup(String account, String clusterName, String nodegroupName) {
        return eks(account, REGION).body(Map.of(
                        "nodegroupName", nodegroupName,
                        "nodeRole", "arn:aws:iam::" + account + ":role/node",
                        "subnets", List.of("subnet-0123456789abcdef0")))
                .post("/clusters/" + clusterName + "/node-groups").then().statusCode(200)
                .extract().path("nodegroup.resources.autoScalingGroups[0].name");
    }
}
