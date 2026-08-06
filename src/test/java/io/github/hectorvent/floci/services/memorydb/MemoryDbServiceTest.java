package io.github.hectorvent.floci.services.memorydb;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.elasticache.proxy.SigV4Validator;
import io.github.hectorvent.floci.services.memorydb.container.MemoryDbContainerHandle;
import io.github.hectorvent.floci.services.memorydb.container.MemoryDbContainerManager;
import io.github.hectorvent.floci.services.memorydb.model.Acl;
import io.github.hectorvent.floci.services.memorydb.model.AuthMode;
import io.github.hectorvent.floci.services.memorydb.model.Cluster;
import io.github.hectorvent.floci.services.memorydb.model.ClusterStatus;
import io.github.hectorvent.floci.services.memorydb.model.User;
import io.github.hectorvent.floci.services.memorydb.proxy.MemoryDbProxyManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MemoryDbServiceTest {

    private MemoryDbService service;
    private MemoryDbContainerManager containerManager;
    private SigV4Validator sigV4Validator;
    private EmulatorConfig.MemoryDbServiceConfig mdbConfig;

    @BeforeEach
    void setUp() {
        containerManager = mock(MemoryDbContainerManager.class);
        MemoryDbProxyManager proxyManager = mock(MemoryDbProxyManager.class);
        sigV4Validator = mock(SigV4Validator.class);
        StorageFactory storageFactory = mock(StorageFactory.class);
        EmulatorConfig config = mock(EmulatorConfig.class);
        RegionResolver regionResolver = mock(RegionResolver.class);

        EmulatorConfig.ServicesConfig servicesConfig = mock(EmulatorConfig.ServicesConfig.class);
        mdbConfig = mock(EmulatorConfig.MemoryDbServiceConfig.class);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.memorydb()).thenReturn(mdbConfig);
        when(mdbConfig.proxyBasePort()).thenReturn(16400);
        when(mdbConfig.proxyMaxPort()).thenReturn(16419);
        when(mdbConfig.defaultImage()).thenReturn("valkey/valkey:8");
        when(config.hostname()).thenReturn(Optional.of("localhost"));
        when(regionResolver.getAccountId()).thenReturn("000000000000");
        when(regionResolver.buildArn(anyString(), anyString(), anyString())).thenAnswer(inv ->
                AwsArnUtils.Arn.of(inv.getArgument(0), inv.getArgument(1),
                        "000000000000", inv.getArgument(2)).toString());

        when(storageFactory.create(anyString(), anyString(), any())).thenAnswer(inv -> new InMemoryStorage<>());
        when(containerManager.start(anyString(), anyString()))
                .thenReturn(new MemoryDbContainerHandle("cid", "cluster", "localhost", 6379));
        doNothing().when(proxyManager).startProxy(anyString(), anyBoolean(), anyInt(), anyString(), anyInt(), any());

        service = new MemoryDbService(containerManager, proxyManager, sigV4Validator,
                storageFactory, config, regionResolver);
    }

    @Test
    void createAndDescribeCluster() {
        Cluster spec = new Cluster();
        spec.setName("my-cluster");
        spec.setNodeType("db.t4g.small");
        spec.setAclName("open-access");
        Cluster created = service.createCluster(spec, "us-east-1");

        assertEquals("my-cluster", created.getName());
        assertEquals(ClusterStatus.AVAILABLE, created.getStatus());
        assertEquals("arn:aws:memorydb:us-east-1:000000000000:cluster/my-cluster", created.getArn());
        assertEquals("localhost", created.getClusterEndpoint().address());

        assertEquals(1, service.describeClusters("my-cluster").size());
        assertEquals(1, service.describeClusters(null).size());
    }

    @Test
    void duplicateClusterRejected() {
        Cluster spec = new Cluster();
        spec.setName("dupe");
        spec.setAclName("open-access");
        service.createCluster(spec, "us-east-1");

        Cluster again = new Cluster();
        again.setName("dupe");
        again.setAclName("open-access");
        assertThrows(AwsException.class, () -> service.createCluster(again, "us-east-1"));
    }

    @Test
    void deleteClusterRemovesIt() {
        Cluster spec = new Cluster();
        spec.setName("temp");
        spec.setAclName("open-access");
        service.createCluster(spec, "us-east-1");

        service.deleteCluster("temp");

        assertThrows(AwsException.class, () -> service.getCluster("temp"));
    }

    @Test
    void tagAndUntagResource() {
        Cluster spec = new Cluster();
        spec.setName("tagged");
        spec.setAclName("open-access");
        Cluster created = service.createCluster(spec, "us-east-1");
        String arn = created.getArn();

        service.tagResource(arn, Map.of("env", "dev"));
        assertEquals("dev", service.listTags(arn).get("env"));

        service.untagResource(arn, List.of("env"));
        assertFalse(service.listTags(arn).containsKey("env"));
    }

    @Test
    void openAccessClusterRequiresNoAuth() {
        Cluster spec = new Cluster();
        spec.setName("open");
        spec.setAclName("open-access");
        service.createCluster(spec, "us-east-1");

        // open-access maps to the no-auth path: any AUTH attempt is accepted
        assertTrue(service.authenticate("open", null, "anything"));
    }

    @Test
    void passwordAuthResolvesThroughAclUser() {
        User userSpec = new User();
        userSpec.setName("app-user");
        userSpec.setAuthMode(AuthMode.PASSWORD);
        userSpec.setPasswords(List.of("s3cret"));
        userSpec.setAccessString("on ~* +@all");
        service.createUser(userSpec, "us-east-1");

        Acl aclSpec = new Acl();
        aclSpec.setName("app-acl");
        aclSpec.setUserNames(List.of("default", "app-user"));
        service.createAcl(aclSpec, "us-east-1");

        Cluster spec = new Cluster();
        spec.setName("secure");
        spec.setAclName("app-acl");
        service.createCluster(spec, "us-east-1");

        assertTrue(service.authenticate("secure", "app-user", "s3cret"));
        assertFalse(service.authenticate("secure", "app-user", "wrong"));
        assertFalse(service.authenticate("secure", "unknown-user", "s3cret"));
    }

    @Test
    void iamAuthDelegatesToSigV4Validator() {
        when(sigV4Validator.validate(anyString(), anyString(), anyString())).thenReturn(true);

        User userSpec = new User();
        userSpec.setName("iam-user");
        userSpec.setAuthMode(AuthMode.IAM);
        userSpec.setAccessString("on ~* +@all");
        service.createUser(userSpec, "us-east-1");

        Acl aclSpec = new Acl();
        aclSpec.setName("iam-acl");
        aclSpec.setUserNames(List.of("default", "iam-user"));
        service.createAcl(aclSpec, "us-east-1");

        Cluster spec = new Cluster();
        spec.setName("iam-cluster");
        spec.setAclName("iam-acl");
        service.createCluster(spec, "us-east-1");

        assertTrue(service.authenticate("iam-cluster", "iam-user", "presigned-token"));
    }

    @Test
    void createClusterWithUnknownAclRejected() {
        Cluster spec = new Cluster();
        spec.setName("bad");
        spec.setAclName("does-not-exist");
        assertThrows(AwsException.class, () -> service.createCluster(spec, "us-east-1"));
    }

    @Test
    void passwordUserRequiresPassword() {
        User userSpec = new User();
        userSpec.setName("no-pass");
        userSpec.setAuthMode(AuthMode.PASSWORD);
        userSpec.setAccessString("on ~* +@all");
        assertThrows(AwsException.class, () -> service.createUser(userSpec, "us-east-1"));
    }

    @Test
    void createClusterRequiresAclName() {
        Cluster spec = new Cluster();
        spec.setName("no-acl");
        AwsException ex = assertThrows(AwsException.class, () -> service.createCluster(spec, "us-east-1"));
        assertEquals(400, ex.getHttpStatus());
    }

    @Test
    void createUserRequiresAccessString() {
        User userSpec = new User();
        userSpec.setName("no-access");
        userSpec.setAuthMode(AuthMode.PASSWORD);
        userSpec.setPasswords(List.of("p"));
        assertThrows(AwsException.class, () -> service.createUser(userSpec, "us-east-1"));
    }

    @Test
    void createUserRejectsNoPasswordType() {
        // "no-password" is a valid wire enum value but is output-only: AWS rejects it on
        // CreateUser ("all newly-created users require a password" / IAM).
        User userSpec = new User();
        userSpec.setName("npr");
        userSpec.setAuthMode(AuthMode.NO_PASSWORD);
        userSpec.setAccessString("on ~* +@all");
        AwsException ex = assertThrows(AwsException.class, () -> service.createUser(userSpec, "us-east-1"));
        assertEquals("InvalidParameterValueException", ex.jsonType());
    }

    @Test
    void createUserRejectsInvalidUserName() {
        User userSpec = new User();
        userSpec.setName("1-bad-start"); // must start with a letter
        userSpec.setAuthMode(AuthMode.PASSWORD);
        userSpec.setPasswords(List.of("p"));
        userSpec.setAccessString("on ~* +@all");
        AwsException ex = assertThrows(AwsException.class, () -> service.createUser(userSpec, "us-east-1"));
        assertEquals("InvalidParameterValueException", ex.jsonType());
    }

    @Test
    void createAclRequiresDefaultUser() {
        User userSpec = new User();
        userSpec.setName("solo");
        userSpec.setAuthMode(AuthMode.PASSWORD);
        userSpec.setPasswords(List.of("p"));
        userSpec.setAccessString("on ~* +@all");
        service.createUser(userSpec, "us-east-1");

        Acl aclSpec = new Acl();
        aclSpec.setName("no-default-acl");
        aclSpec.setUserNames(List.of("solo")); // missing the required "default" user
        AwsException ex = assertThrows(AwsException.class, () -> service.createAcl(aclSpec, "us-east-1"));
        assertEquals("DefaultUserRequired", ex.jsonType());
    }

    @Test
    void createAclRejectsDuplicateUserNames() {
        User userSpec = new User();
        userSpec.setName("dup");
        userSpec.setAuthMode(AuthMode.PASSWORD);
        userSpec.setPasswords(List.of("p"));
        userSpec.setAccessString("on ~* +@all");
        service.createUser(userSpec, "us-east-1");

        Acl aclSpec = new Acl();
        aclSpec.setName("dup-acl");
        aclSpec.setUserNames(List.of("default", "dup", "dup"));
        AwsException ex = assertThrows(AwsException.class, () -> service.createAcl(aclSpec, "us-east-1"));
        assertEquals("DuplicateUserNameFault", ex.jsonType());
    }

    @Test
    void describeIncludesBuiltinDefaults() {
        assertTrue(service.describeUsers(null, "us-east-1").stream()
                .anyMatch(u -> "default".equals(u.getName())));
        assertTrue(service.describeAcls(null, "us-east-1").stream()
                .anyMatch(a -> "open-access".equals(a.getName())));
    }

    @Test
    void builtinDefaultsCannotBeDeleted() {
        assertThrows(AwsException.class, () -> service.deleteUser("default"));
        assertThrows(AwsException.class, () -> service.deleteAcl("open-access"));
    }

    @Test
    void aclInUseCannotBeDeleted() {
        User userSpec = new User();
        userSpec.setName("u1");
        userSpec.setAuthMode(AuthMode.PASSWORD);
        userSpec.setPasswords(List.of("p"));
        userSpec.setAccessString("on ~* +@all");
        service.createUser(userSpec, "us-east-1");

        Acl aclSpec = new Acl();
        aclSpec.setName("in-use");
        aclSpec.setUserNames(List.of("default", "u1"));
        service.createAcl(aclSpec, "us-east-1");

        Cluster spec = new Cluster();
        spec.setName("c");
        spec.setAclName("in-use");
        service.createCluster(spec, "us-east-1");

        assertThrows(AwsException.class, () -> service.deleteAcl("in-use"));
    }

    @Test
    void nullClusterNameYieldsValidationErrorNotNpe() {
        AwsException ex = assertThrows(AwsException.class, () -> service.getCluster(null));
        assertEquals(400, ex.getHttpStatus());
        assertThrows(AwsException.class, () -> service.deleteCluster(null));
        assertThrows(AwsException.class, () -> service.updateCluster("  ", "desc"));
    }

    @Test
    void failedProvisioningReleasesProxyPort() {
        when(mdbConfig.proxyBasePort()).thenReturn(16400);
        when(mdbConfig.proxyMaxPort()).thenReturn(16400); // exactly one port available
        when(containerManager.start(anyString(), anyString()))
                .thenThrow(new RuntimeException("docker unavailable"))
                .thenReturn(new MemoryDbContainerHandle("cid", "c2", "localhost", 6379));

        Cluster first = new Cluster();
        first.setName("c1");
        first.setAclName("open-access");
        assertThrows(RuntimeException.class, () -> service.createCluster(first, "us-east-1"));

        // The single port must have been released, so a second create still succeeds
        Cluster second = new Cluster();
        second.setName("c2");
        second.setAclName("open-access");
        Cluster created = service.createCluster(second, "us-east-1");
        assertEquals(ClusterStatus.AVAILABLE, created.getStatus());
    }

    @Test
    void mockModeSkipsContainerAndReportsStandardPort() {
        when(mdbConfig.mock()).thenReturn(true);

        Cluster spec = new Cluster();
        spec.setName("mock-cluster");
        spec.setAclName("open-access");
        Cluster created = service.createCluster(spec, "us-east-1");

        assertEquals(ClusterStatus.AVAILABLE, created.getStatus());
        assertEquals("localhost", created.getClusterEndpoint().address());
        assertEquals(6379, created.getClusterEndpoint().port());
        verifyNoInteractions(containerManager);
    }
}
