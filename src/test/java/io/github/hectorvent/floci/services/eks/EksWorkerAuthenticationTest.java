package io.github.hectorvent.floci.services.eks;

import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import io.github.hectorvent.floci.services.eks.model.AccessConfig;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.ClusterStatus;
import io.github.hectorvent.floci.services.eks.model.CreateAccessEntryRequest;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.IamService.EksSessionIdentity;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.services.iam.model.InstanceProfile;
import io.github.hectorvent.floci.testutil.SigV4TokenTestHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EksWorkerAuthenticationTest {
    private static final String ACCOUNT = "123456789012";
    private static final String REGION = "us-east-1";
    private static final String ROLE = "arn:aws:iam::" + ACCOUNT + ":role/path/worker";
    private static final String KEY = "ASIAWORKER";
    private static final Instant CREATED = Instant.parse("2026-09-17T00:00:00Z");
    private final IamService iam = mock(IamService.class);
    private final EksService eks = mock(EksService.class);
    private final Ec2Service ec2 = mock(Ec2Service.class);
    private final EksAccessEntryService entries = new EksAccessEntryService(new InMemoryStorage<>(), iam);
    private final Cluster cluster = new Cluster();
    private final Instance instance = new Instance();
    private final IamRole role = new IamRole("AROA-original", "worker", "/path/", ROLE, "{}");
    private final EksWorkerAuthentication auth = new EksWorkerAuthentication(iam, eks, ec2, entries);
    private final EksTokenValidator.VerifiedToken verified = new EksTokenValidator.VerifiedToken(KEY, REGION);

    @BeforeEach
    void setup() {
        cluster.setName("demo");
        cluster.setArn("arn:aws:eks:" + REGION + ":" + ACCOUNT + ":cluster/demo");
        cluster.setCreatedAt(CREATED);
        cluster.setStatus(ClusterStatus.ACTIVE);
        cluster.setAccessConfig(new AccessConfig("API", false));
        instance.setInstanceId("i-worker");
        instance.setRegion(REGION);
        instance.setState(InstanceState.running());
        instance.setPrivateDnsName("ip-10-0-0-1.ec2.internal");
        instance.setIamInstanceProfileArn("arn:aws:iam::" + ACCOUNT + ":instance-profile/path/worker-profile");
        InstanceProfile profile = new InstanceProfile("AIPA", "worker-profile", "/path/", instance.getIamInstanceProfileArn());
        profile.setRoleNames(List.of("worker"));
        when(iam.findRole(ACCOUNT, "worker")).thenReturn(Optional.of(role));
        when(iam.findInstanceProfile(ACCOUNT, "worker-profile")).thenReturn(Optional.of(profile));
        when(iam.findEksSessionIdentity(KEY)).thenReturn(Optional.of(
                new EksSessionIdentity(ACCOUNT, ROLE, role.getRoleId(), instance.getInstanceId())));
        when(eks.findAuthenticationCluster(ACCOUNT, "demo")).thenReturn(Optional.of(cluster));
        when(ec2.findInstanceForAccount(ACCOUNT, REGION, "i-worker")).thenReturn(Optional.of(instance));
        createEntry("EC2_LINUX");
    }

    @Test
    void signedWorkerTokenProducesNodeIdentityWithoutAdministratorGroup() throws Exception {
        when(iam.findSecretKey(KEY, "session-token")).thenReturn(Optional.of("worker-secret"));
        String token = SigV4TokenTestHelper.createEksToken("demo", KEY, "worker-secret", Instant.now(), 60, "session-token");
        EksTokenWebhookController controller = new EksTokenWebhookController(new EksTokenValidator(iam), auth);
        Map<?, ?> response = (Map<?, ?>) controller.review("demo", ACCOUNT, REGION, CREATED.toString(),
                Map.of("spec", Map.of("token", token))).getEntity();
        Map<?, ?> status = (Map<?, ?>) response.get("status");
        assertEquals(true, status.get("authenticated"));
        Map<?, ?> user = (Map<?, ?>) status.get("user");
        assertEquals("system:node:ip-10-0-0-1.ec2.internal", user.get("username"));
        assertEquals(List.of("system:bootstrappers", "system:nodes"), user.get("groups"));
        when(iam.findSecretKey(KEY, "session-token")).thenReturn(Optional.empty());
        response = (Map<?, ?>) controller.review("demo", ACCOUNT, REGION, CREATED.toString(),
                Map.of("spec", Map.of("token", token))).getEntity();
        assertEquals(Map.of("authenticated", false), response.get("status"));
    }

    @Test
    void missingOrStandardEntryDoesNotFallBackToAdministrator() {
        entries.delete(cluster, ROLE);
        assertTrue(authenticate().isEmpty());
        createEntry("STANDARD");
        assertTrue(authenticate().isEmpty());
    }

    @Test
    void changedRoleIdInvalidatesEntryAndOldInstanceCredentials() {
        role.setRoleId("AROA-recreated");
        assertTrue(authenticate().isEmpty());
        when(iam.findEksSessionIdentity(KEY)).thenReturn(Optional.of(
                new EksSessionIdentity(ACCOUNT, ROLE, role.getRoleId(), "i-worker")));
        assertTrue(authenticate().isEmpty());
        entries.delete(cluster, ROLE);
        createEntry("EC2_LINUX");
        assertTrue(authenticate().isPresent());
    }

    @Test
    void missingStoppedOrUnprofiledInstanceIsRejected() {
        instance.setState(InstanceState.terminated());
        assertTrue(authenticate().isEmpty());
        instance.setState(InstanceState.running());
        instance.setIamInstanceProfileArn(null);
        assertTrue(authenticate().isEmpty());
        when(ec2.findInstanceForAccount(ACCOUNT, REGION, "i-worker")).thenReturn(Optional.empty());
        assertTrue(authenticate().isEmpty());
    }

    @Test
    void wrongTargetOrClusterIncarnationIsRejected() {
        assertTrue(auth.authenticate(verified, "demo", "999999999999", REGION, CREATED.toString()).isEmpty());
        assertTrue(auth.authenticate(verified, "demo", ACCOUNT, "us-west-2", CREATED.toString()).isEmpty());
        assertTrue(auth.authenticate(verified, "other", ACCOUNT, REGION, CREATED.toString()).isEmpty());
        assertTrue(auth.authenticate(verified, "demo", null, null, null).isEmpty());
        cluster.setCreatedAt(CREATED.plusSeconds(1));
        assertTrue(authenticate().isEmpty());
        assertTrue(auth.authenticate(verified, "demo", ACCOUNT, REGION, cluster.getCreatedAt().toString()).isEmpty());
    }

    @Test
    void configMapAndInactiveClustersRejectWorkers() {
        cluster.setStatus(ClusterStatus.CREATING);
        assertTrue(authenticate().isEmpty());
        cluster.setStatus(ClusterStatus.ACTIVE);
        cluster.setAccessConfig(new AccessConfig("CONFIG_MAP", false));
        assertTrue(authenticate().isEmpty());
    }

    @Test
    void revokedSessionDoesNotFallBackToAdministrator() {
        when(iam.findEksSessionIdentity(KEY)).thenReturn(Optional.empty());
        assertTrue(authenticate().isEmpty());
    }

    @Test
    void missingRoleIdOrPrivateDnsIsRejected() {
        instance.setPrivateDnsName("");
        assertTrue(authenticate().isEmpty());
        when(iam.findEksSessionIdentity(KEY)).thenReturn(Optional.of(new EksSessionIdentity(ACCOUNT, ROLE, null, "i-worker")));
        assertTrue(authenticate().isEmpty());
    }

    private Optional<Map<String, Object>> authenticate() {
        return auth.authenticate(verified, "demo", ACCOUNT, REGION, CREATED.toString());
    }

    private void createEntry(String type) {
        entries.create(cluster, new CreateAccessEntryRequest(ROLE, type, null, null, null, null));
    }
}
