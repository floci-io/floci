package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.iam.model.IamGroup;
import io.github.hectorvent.floci.services.iam.model.IamPolicy;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.services.iam.model.IamUser;
import io.github.hectorvent.floci.services.iam.model.InstanceProfile;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IamCaseInsensitiveLegacyNamesTest {

    private static final String DEFAULT_ACCOUNT = "000000000000";
    private static final String OTHER_ACCOUNT = "111111111111";
    private static final String LEGACY_NAME = "LegacyName";
    private static final String LEGACY_POLICY_ARN =
            "arn:aws:iam::000000000000:policy/legacy/LegacyName";

    private final StorageBackend<String, IamUser> users = new InMemoryStorage<>();
    private final StorageBackend<String, IamGroup> groups = new InMemoryStorage<>();
    private final StorageBackend<String, IamRole> roles = new InMemoryStorage<>();
    private final StorageBackend<String, IamPolicy> policies = new InMemoryStorage<>();
    private final StorageBackend<String, InstanceProfile> profiles = new InMemoryStorage<>();

    enum ResourceType { USER, GROUP, ROLE, POLICY, INSTANCE_PROFILE }

    @BeforeEach
    void seedUnprefixedLegacyResources() {
        IamService legacyService = new IamService(users, groups, roles, policies,
                new InMemoryStorage<>(), profiles, new InMemoryStorage<>(),
                new RegionResolver("us-east-1", DEFAULT_ACCOUNT));
        for (ResourceType type : ResourceType.values()) {
            create(legacyService, type, LEGACY_NAME);
        }
    }

    @ParameterizedTest
    @EnumSource(ResourceType.class)
    void createRejectsExactAndCaseVariantOfUnmigratedLegacyName(ResourceType type) {
        IamService service = serviceFor(DEFAULT_ACCOUNT);
        String originalId = legacyId(type);

        assertAlreadyExists(() -> create(service, type, LEGACY_NAME));
        assertAlreadyExists(() -> create(service, type, "legacyname"));

        assertEquals(originalId, legacyId(type));
        assertEquals(originalId, readId(service, type, DEFAULT_ACCOUNT));
    }

    @ParameterizedTest
    @EnumSource(ResourceType.class)
    void anotherAccountCanReuseLegacyNameWithoutClaimingLegacyResource(ResourceType type) {
        String originalId = legacyId(type);
        IamService otherService = serviceFor(OTHER_ACCOUNT);

        String otherId = create(otherService, type, LEGACY_NAME);

        assertNotEquals(originalId, otherId);
        assertEquals(originalId, legacyId(type));
        assertEquals(otherId, readId(otherService, type, OTHER_ACCOUNT));
        IamService defaultService = serviceFor(DEFAULT_ACCOUNT);
        assertAlreadyExists(() -> create(defaultService, type, "legacyname"));
        assertEquals(originalId, readId(defaultService, type, DEFAULT_ACCOUNT));
    }

    @Test
    void updateUserRejectsUnmigratedLegacyNameAndPreservesSource() {
        IamService service = serviceFor(DEFAULT_ACCOUNT);
        IamUser source = service.createUser("RenameSource", "/source/");
        String originalId = legacyId(ResourceType.USER);

        assertAlreadyExists(() -> service.updateUser("RenameSource", LEGACY_NAME, null));
        assertAlreadyExists(() -> service.updateUser("RenameSource", "legacyname", null));

        assertEquals(source.getUserId(), service.getUser("RenameSource").getUserId());
        assertEquals("RenameSource", source.getUserName());
        assertEquals("/source/", source.getPath());
        assertEquals(originalId, legacyId(ResourceType.USER));
    }

    @Test
    void updateGroupRejectsUnmigratedLegacyNameAndPreservesSource() {
        IamService service = serviceFor(DEFAULT_ACCOUNT);
        IamGroup source = service.createGroup("RenameSource", "/source/");
        String originalId = legacyId(ResourceType.GROUP);

        assertAlreadyExists(() -> service.updateGroup("RenameSource", LEGACY_NAME, null));
        assertAlreadyExists(() -> service.updateGroup("RenameSource", "legacyname", null));

        assertEquals(source.getGroupId(), service.getGroup("RenameSource").getGroupId());
        assertEquals("RenameSource", source.getGroupName());
        assertEquals("/source/", source.getPath());
        assertEquals(originalId, legacyId(ResourceType.GROUP));
    }

    @SuppressWarnings("unchecked")
    private IamService serviceFor(String accountId) {
        RequestContext context = new RequestContext();
        context.setAccountId(accountId);
        Instance<RequestContext> contexts = mock(Instance.class);
        when(contexts.get()).thenReturn(context);
        return new IamService(scoped(users, contexts), scoped(groups, contexts), scoped(roles, contexts),
                scoped(policies, contexts), new InMemoryStorage<>(), scoped(profiles, contexts),
                new InMemoryStorage<>(), new RegionResolver("us-east-1", accountId));
    }

    private static <T> AccountAwareStorageBackend<T> scoped(StorageBackend<String, T> raw,
                                                           Instance<RequestContext> contexts) {
        return new AccountAwareStorageBackend<>(raw, contexts, DEFAULT_ACCOUNT);
    }

    private static String create(IamService service, ResourceType type, String name) {
        return switch (type) {
            case USER -> service.createUser(name, "/legacy/").getUserId();
            case GROUP -> service.createGroup(name, "/legacy/").getGroupId();
            case ROLE -> service.createRole(name, "/legacy/", "{}", null, 3600, null).getRoleId();
            case POLICY -> service.createPolicy(name, "/legacy/", null, "{}", null).getPolicyId();
            case INSTANCE_PROFILE -> service.createInstanceProfile(name, "/legacy/").getInstanceProfileId();
        };
    }

    private String legacyId(ResourceType type) {
        return switch (type) {
            case USER -> users.get(LEGACY_NAME).orElseThrow().getUserId();
            case GROUP -> groups.get(LEGACY_NAME).orElseThrow().getGroupId();
            case ROLE -> roles.get(LEGACY_NAME).orElseThrow().getRoleId();
            case POLICY -> policies.get(LEGACY_POLICY_ARN).orElseThrow().getPolicyId();
            case INSTANCE_PROFILE -> profiles.get(LEGACY_NAME).orElseThrow().getInstanceProfileId();
        };
    }

    private static String readId(IamService service, ResourceType type, String accountId) {
        return switch (type) {
            case USER -> service.getUser(LEGACY_NAME).getUserId();
            case GROUP -> service.getGroup(LEGACY_NAME).getGroupId();
            case ROLE -> service.getRole(LEGACY_NAME).getRoleId();
            case POLICY -> service.getPolicy("arn:aws:iam::" + accountId + ":policy/legacy/" + LEGACY_NAME)
                    .getPolicyId();
            case INSTANCE_PROFILE -> service.getInstanceProfile(LEGACY_NAME).getInstanceProfileId();
        };
    }

    private static void assertAlreadyExists(Executable operation) {
        AwsException exception = assertThrows(AwsException.class, operation);
        assertEquals("EntityAlreadyExists", exception.getErrorCode());
        assertEquals(409, exception.getHttpStatus());
    }
}
