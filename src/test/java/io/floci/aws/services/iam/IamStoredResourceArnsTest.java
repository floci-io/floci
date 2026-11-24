package io.floci.aws.services.iam;

import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.SshPublicKeys;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.iam.model.IamRole;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What IAM gives the request-resources table of its own resources: the ARN a name names, path included, the user a key
 * or a credential belongs to, the role a deletion task deleted and the user who signs a request.
 */
class IamStoredResourceArnsTest {

    private static final String ACCOUNT = "000000000000";
    private static final String TRUST_POLICY = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
            + "\"Principal\":{\"Service\":\"ec2.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}";

    private IamService iam;
    private IamStoredResourceArns stored;

    @BeforeEach
    void setUp() {
        iam = new IamService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new RegionResolver("us-east-1", ACCOUNT), false, null);
        @SuppressWarnings("unchecked")
        Instance<IamService> service = mock(Instance.class);
        when(service.get()).thenReturn(iam);
        @SuppressWarnings("unchecked")
        Instance<DelegationRequestOwnership> delegations = mock(Instance.class);
        when(delegations.isUnsatisfied()).thenReturn(true);
        stored = new IamStoredResourceArns(delegations, service, new RegionResolver("us-east-1", ACCOUNT));
    }

    @Test
    void aNameNamesTheArnItIsStoredUnderPathIncluded() {
        iam.createUser("bob", "/team/");
        iam.createRole("app", "/service-role/", TRUST_POLICY, null, 0, null);
        assertEquals(Optional.of("arn:aws:iam::000000000000:user/team/bob"), stored.find("user", List.of("bob"), ""));
        assertEquals(Optional.of("arn:aws:iam::000000000000:role/service-role/app"),
                stored.find("role", List.of("app"), ""));
        assertEquals(Optional.empty(), stored.find("user", List.of("alice"), ""));
        assertEquals(Optional.empty(), stored.find("policy", List.of("bob"), ""));
    }

    @Test
    void aKeyOrACredentialNamesTheUserItBelongsTo() {
        iam.createUser("bob", "/team/");
        String bob = "arn:aws:iam::000000000000:user/team/bob";
        String accessKey = iam.createAccessKey("bob").getAccessKeyId();
        String sshKey = iam.uploadSshPublicKey("bob", SshPublicKeys.toOpenSsh(generated())).getSshPublicKeyId();
        String credential = iam.createServiceSpecificCredential("bob", "codecommit.amazonaws.com", null)
                .getServiceSpecificCredentialId();
        assertEquals(List.of(bob), stored.through("AccessKeyId", accessKey, ""));
        assertEquals(List.of(bob), stored.through("SSHPublicKeyId", sshKey, ""));
        assertEquals(List.of(bob), stored.through("ServiceSpecificCredentialId", credential, ""));
        assertEquals(List.of(), stored.through("AccessKeyId", "AKIANOTHELD", ""));
        assertEquals(List.of(), stored.through("UserName", "bob", ""));
    }

    @Test
    void aDeletionTaskNamesTheRoleItDeleted() {
        IamRole role = iam.createServiceLinkedRole("es.amazonaws.com", null, null);
        String task = iam.deleteServiceLinkedRole(role.getRoleName());
        assertEquals(List.of(role.getArn()), stored.through("DeletionTaskId", task, ""));
        assertEquals(List.of(), stored.through("DeletionTaskId", "task/aws-service-role/es.amazonaws.com/x/y", ""));
    }

    @Test
    void aSigningKeyNamesItsUser() {
        iam.createUser("bob", "/team/");
        String accessKey = iam.createAccessKey("bob").getAccessKeyId();
        assertEquals(Optional.of("arn:aws:iam::000000000000:user/team/bob"), stored.signedBy(accessKey));
        assertEquals(Optional.empty(), stored.signedBy("AKIANOTHELD"));
    }

    private static RSAPublicKey generated() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return (RSAPublicKey) generator.generateKeyPair().getPublic();
        } catch (Exception e) {
            throw new IllegalStateException("could not generate a key", e);
        }
    }
}
