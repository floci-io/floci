package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.eks.EksService;
import io.github.hectorvent.floci.services.iam.model.AccessKey;
import io.github.hectorvent.floci.services.iam.model.IamGroup;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.services.iam.model.IamUser;
import io.github.hectorvent.floci.services.iam.model.InstanceProfile;
import io.github.hectorvent.floci.services.iam.model.ServerCertificate;
import io.github.hectorvent.floci.services.kms.KmsService;
import io.github.hectorvent.floci.services.lambdamicrovms.LambdaMicrovmsService;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResourceArnBuilderTest {

    private static final String ACCOUNT = "000000000000";

    private ResourceArnBuilder builder;
    private ContainerRequestContext ctx;
    private UriInfo uriInfo;
    private MultivaluedMap<String, String> queryParams;
    private Map<String, Object> contextProperties;

    @BeforeEach
    void setUp() {
        builder = new ResourceArnBuilder(new ObjectMapper());
        ctx = mock(ContainerRequestContext.class);
        uriInfo = mock(UriInfo.class);
        queryParams = new MultivaluedHashMap<>();
        contextProperties = new HashMap<>();

        when(ctx.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("/");
        when(uriInfo.getQueryParameters()).thenReturn(queryParams);

        when(ctx.getProperty(any())).thenAnswer(inv -> contextProperties.get(inv.getArgument(0)));
        doAnswer(inv -> {
            contextProperties.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(ctx).setProperty(any(), any());
    }

    /** Query-protocol services carry their parameters in a form body, as IAM does. */
    private void setFormBody(String form) {
        contextProperties.clear();
        byte[] bytes = form.getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream in = new ByteArrayInputStream(bytes);
        when(ctx.getMediaType()).thenReturn(MediaType.APPLICATION_FORM_URLENCODED_TYPE);
        when(ctx.getEntityStream()).thenReturn(in);
        doAnswer(inv -> {
            InputStream newIn = inv.getArgument(0);
            when(ctx.getEntityStream()).thenReturn(newIn);
            return null;
        }).when(ctx).setEntityStream(any(InputStream.class));
    }

    // ── IAM ──────────────────────────────────────────────────────────────────────

    /**
     * A builder whose store holds one certificate under {@code name} with {@code arn}, which is
     * what the check reads for every operation acting on a certificate that already exists.
     */
    private ResourceArnBuilder backedBy(String name, String arn) {
        ServerCertificate stored = new ServerCertificate();
        stored.setServerCertificateName(name);
        stored.setArn(arn);
        IamService service = mock(IamService.class);
        when(service.findServerCertificate(name)).thenReturn(Optional.of(stored));
        @SuppressWarnings("unchecked")
        Instance<IamService> instance = mock(Instance.class);
        when(instance.get()).thenReturn(service);
        return new ResourceArnBuilder(new ObjectMapper(), instance);
    }

    /** A builder reading {@code service}, for the actions that name a resource that exists. */
    private static ResourceArnBuilder backedBy(IamService service) {
        @SuppressWarnings("unchecked")
        Instance<IamService> instance = mock(Instance.class);
        when(instance.get()).thenReturn(service);
        return new ResourceArnBuilder(new ObjectMapper(), instance);
    }

    /** A builder reading {@code microvms}, for an action on a running MicroVM. */
    private static ResourceArnBuilder backedBy(LambdaMicrovmsService microvms) {
        @SuppressWarnings("unchecked")
        Instance<LambdaMicrovmsService> instance = mock(Instance.class);
        when(instance.get()).thenReturn(microvms);
        return new ResourceArnBuilder(new ObjectMapper(), null, null, instance);
    }

    /** A builder reading {@code secrets}, for a secret named by its name. */
    private static ResourceArnBuilder backedBy(SecretsManagerService secrets) {
        @SuppressWarnings("unchecked")
        Instance<SecretsManagerService> instance = mock(Instance.class);
        when(instance.get()).thenReturn(secrets);
        return new ResourceArnBuilder(new ObjectMapper(), null, instance);
    }

    /** A builder reading {@code eks}, for an EKS route naming a cluster or what it holds. */
    private static ResourceArnBuilder backedBy(EksService eks) {
        @SuppressWarnings("unchecked")
        Instance<EksService> instance = mock(Instance.class);
        when(instance.get()).thenReturn(eks);
        return new ResourceArnBuilder(new ObjectMapper(), null, null, null, instance);
    }

    private static IamService storeHolding(String userName, String path, String arn) {
        IamUser user = new IamUser();
        user.setUserName(userName);
        user.setPath(path);
        user.setArn(arn);
        IamService service = mock(IamService.class);
        when(service.findUser(ACCOUNT, userName)).thenReturn(Optional.of(user));
        return service;
    }

    /** The ARN the builder gives for the request in {@code form}, with one resource expected. */
    private String resourceOf(ResourceArnBuilder resourceArnBuilder, String form) {
        setFormBody(form);
        return resourceArnBuilder.build("iam", ctx, "us-east-1", ACCOUNT);
    }

    /**
     * The gap issue 4979 describes, for the type most of IAM's actions take. A deny naming one
     * user has to bind to that user, which it cannot do while the action is evaluated against
     * {@code *}.
     */
    @Test
    void iamNamesTheUserBeingActedOn() {
        String arn = "arn:aws:iam::000000000000:user/eng/bob";
        IamService store = storeHolding("bob", "/eng/", arn);
        for (String action : List.of("DeleteUser", "GetUser", "CreateAccessKey", "PutUserPolicy",
                "AttachUserPolicy", "TagUser", "EnableMFADevice", "UpdateAccessKey")) {
            assertEquals(arn, resourceOf(backedBy(store), "Action=" + action + "&UserName=bob"),
                    action);
        }
    }

    /**
     * The stored path is part of the ARN, as {@code user/${UserNameWithPath}} in the reference's
     * ARN format says. A user at {@code /eng/} is not named by the ARN without it, so reading the
     * path from the store rather than deriving a root-path ARN is what makes a scoped deny bind.
     */
    @Test
    void iamReadsThePathOfTheStoredUser() {
        String arn = "arn:aws:iam::000000000000:user/eng/deep/bob";
        assertEquals(arn,
                resourceOf(backedBy(storeHolding("bob", "/eng/deep/", arn)),
                        "Action=GetUser&UserName=bob"));
    }

    /**
     * The delegation family passes an id where the ARN-carrying actions pass an ARN, so its ARN is
     * minted from the request. Without this the actions fall back to {@code *} and a policy naming
     * one request never applies, which is the fail-open {@code UNRESOLVED_TYPES} used to cover
     * while none of them was dispatched.
     */
    @Test
    void iamNamesTheDelegationRequestABlockOfActionsActsOn() {
        String id = "dr-0123456789abcdef01234567";
        for (String action : List.of("AssociateDelegationRequest", "AcceptDelegationRequest",
                "RejectDelegationRequest", "GetDelegationRequest")) {
            assertEquals("arn:aws:iam::000000000000:delegation-request/" + id,
                    resourceOf(builder, "Action=" + action + "&DelegationRequestId=" + id), action);
        }
    }

    @Test
    void iamLeavesADelegationRequestItIsAboutToMintAtTheWildcard() {
        // IAM assigns the id, so the create names no resource to authorize against.
        assertEquals("*", resourceOf(builder,
                "Action=CreateDelegationRequest&Description=d&RequestorWorkflowId=w"));
        // AWS publishes no authorizable action for the update at all, so it carries no type.
        assertEquals("*", resourceOf(builder,
                "Action=UpdateDelegationRequest&DelegationRequestId=dr-0123456789abcdef01234567"));
    }

    /**
     * Two entities named, one authorized. The reference gives {@code AddUserToGroup} the group as
     * its only resource type, so taking the user from the request would scope it to the wrong one
     * and have a deny on the group pass.
     */
    @Test
    void iamNamesTheGroupRatherThanTheUserOnAGroupMembershipChange() {
        String groupArn = "arn:aws:iam::000000000000:group/admins";
        IamGroup group = new IamGroup();
        group.setGroupName("admins");
        group.setPath("/");
        group.setArn(groupArn);
        IamService store = mock(IamService.class);
        when(store.findGroup(ACCOUNT, "admins")).thenReturn(Optional.of(group));

        for (String action : List.of("AddUserToGroup", "RemoveUserFromGroup")) {
            assertEquals(groupArn,
                    resourceOf(backedBy(store),
                            "Action=" + action + "&GroupName=admins&UserName=bob"),
                    action);
        }
    }

    /** The same shape for instance profiles, whose role actions name the profile only. */
    @Test
    void iamNamesTheInstanceProfileRatherThanTheRoleOnAProfileChange() {
        String profileArn = "arn:aws:iam::000000000000:instance-profile/app";
        InstanceProfile profile = new InstanceProfile();
        profile.setInstanceProfileName("app");
        profile.setPath("/");
        profile.setArn(profileArn);
        IamService store = mock(IamService.class);
        when(store.findInstanceProfile(ACCOUNT, "app")).thenReturn(Optional.of(profile));

        for (String action : List.of("AddRoleToInstanceProfile", "RemoveRoleFromInstanceProfile")) {
            assertEquals(profileArn,
                    resourceOf(backedBy(store),
                            "Action=" + action + "&InstanceProfileName=app&RoleName=app-role"),
                    action);
        }
    }

    @Test
    void iamNamesTheRoleBeingActedOn() {
        String arn = "arn:aws:iam::000000000000:role/service-role/app";
        IamRole role = new IamRole();
        role.setRoleName("app");
        role.setPath("/service-role/");
        role.setArn(arn);
        IamService store = mock(IamService.class);
        when(store.findRole(ACCOUNT, "app")).thenReturn(Optional.of(role));

        assertEquals(arn, resourceOf(backedBy(store), "Action=PutRolePolicy&RoleName=app"));
    }

    /**
     * A create names a resource that does not exist, so its ARN comes from the request's own
     * {@code Path}. Reading a stored path would resolve to the root and let a policy scoped to one
     * path authorize a create into another.
     */
    @Test
    void iamMintsTheArnOfAResourceBeingCreated() {
        assertEquals("arn:aws:iam::000000000000:user/eng/bob",
                resourceOf(builder, "Action=CreateUser&UserName=bob&Path=/eng/"));
        assertEquals("arn:aws:iam::000000000000:role/app",
                resourceOf(builder, "Action=CreateRole&RoleName=app"));
        assertEquals("arn:aws:iam::000000000000:group/admins",
                resourceOf(builder, "Action=CreateGroup&GroupName=admins"));
        assertEquals("arn:aws:iam::000000000000:instance-profile/ops/app",
                resourceOf(builder,
                        "Action=CreateInstanceProfile&InstanceProfileName=app&Path=/ops/"));
        assertEquals("arn:aws:iam::000000000000:policy/team/p",
                resourceOf(builder, "Action=CreatePolicy&PolicyName=p&Path=/team/"));
        assertEquals("arn:aws:iam::000000000000:mfa/bob-key",
                resourceOf(builder,
                        "Action=CreateVirtualMFADevice&VirtualMFADeviceName=bob-key"));
    }

    /** A create is minted in the partition it is being created in, not the commercial one. */
    @Test
    void iamMintsACreateInTheRequestsPartition() {
        setFormBody("Action=CreateUser&UserName=bob");
        assertEquals("arn:aws-cn:iam::000000000000:user/bob",
                builder.build("iam", ctx, "cn-north-1", ACCOUNT));
    }

    /**
     * A policy, MFA device, SAML provider, OIDC provider or role template is named by an ARN the
     * request already carries, so it is passed through. Rebuilding it from parts would only invent
     * a second spelling of the resource the caller already named, and a role template's ARN could
     * not be rebuilt at all: it names the {@code aws} account rather than the caller's, and
     * carries a major version after a colon.
     */
    @Test
    void iamPassesThroughAnArnTheRequestAlreadyCarries() {
        assertEquals("arn:aws:iam::000000000000:policy/team/p",
                resourceOf(builder, "Action=DeletePolicy"
                        + "&PolicyArn=arn%3Aaws%3Aiam%3A%3A000000000000%3Apolicy%2Fteam%2Fp"));
        assertEquals("arn:aws:iam::000000000000:mfa/bob-key",
                resourceOf(builder, "Action=DeleteVirtualMFADevice"
                        + "&SerialNumber=arn%3Aaws%3Aiam%3A%3A000000000000%3Amfa%2Fbob-key"));
        assertEquals("arn:aws:iam::000000000000:saml-provider/corp",
                resourceOf(builder, "Action=GetSAMLProvider"
                        + "&SAMLProviderArn=arn%3Aaws%3Aiam%3A%3A000000000000%3Asaml-provider%2Fcorp"));
        assertEquals("arn:aws:iam::000000000000:oidc-provider/example.com",
                resourceOf(builder, "Action=DeleteOpenIDConnectProvider"
                        + "&OpenIDConnectProviderArn="
                        + "arn%3Aaws%3Aiam%3A%3A000000000000%3Aoidc-provider%2Fexample.com"));
        assertEquals("arn:aws:iam::000000000000:user/bob",
                resourceOf(builder, "Action=SimulatePrincipalPolicy"
                        + "&PolicySourceArn=arn%3Aaws%3Aiam%3A%3A000000000000%3Auser%2Fbob"));
        assertEquals("arn:aws:iam::aws:role-template/ec2.amazonaws.com/AmazonEC2RoleforSSM:1",
                resourceOf(builder, "Action=GetRoleTemplateVersion"
                        + "&TemplateArn=arn%3Aaws%3Aiam%3A%3Aaws%3Arole-template%2F"
                        + "ec2.amazonaws.com%2FAmazonEC2RoleforSSM%3A1"));
    }

    /**
     * A SAML provider's ARN is its bare name and an OIDC provider's is its URL without the
     * scheme, neither of which carries a path. Minting them the way the pathed types are minted
     * would name resources that do not exist.
     */
    @Test
    void iamMintsProviderArnsWithoutAPath() {
        assertEquals("arn:aws:iam::000000000000:saml-provider/corp",
                resourceOf(builder, "Action=CreateSAMLProvider&Name=corp"));
        assertEquals("arn:aws:iam::000000000000:oidc-provider/example.com/app",
                resourceOf(builder, "Action=CreateOpenIDConnectProvider"
                        + "&Url=https%3A%2F%2Fexample.com%2Fapp"));
    }

    /**
     * A user rename names both the old user and the new one, for the reason the API reference
     * gives: the requester "must have appropriate permissions on both the source object and the
     * target object". A group rename says the same in its own words.
     */
    @Test
    void iamNamesBothNamesOfAUserOrGroupRename() {
        String userArn = "arn:aws:iam::000000000000:user/bob";
        setFormBody("Action=UpdateUser&UserName=bob&NewUserName=robert");
        assertEquals(List.of(userArn, "arn:aws:iam::000000000000:user/robert"),
                backedBy(storeHolding("bob", "/", userArn))
                        .buildResources("iam", ctx, "us-east-1", ACCOUNT));

        String groupArn = "arn:aws:iam::000000000000:group/managers";
        IamGroup group = new IamGroup();
        group.setGroupName("managers");
        group.setPath("/");
        group.setArn(groupArn);
        IamService store = mock(IamService.class);
        when(store.findGroup(ACCOUNT, "managers")).thenReturn(Optional.of(group));
        setFormBody("Action=UpdateGroup&GroupName=managers&NewGroupName=mgrs");
        assertEquals(List.of(groupArn, "arn:aws:iam::000000000000:group/mgrs"),
                backedBy(store).buildResources("iam", ctx, "us-east-1", ACCOUNT));
    }

    /**
     * {@code CreateServiceLinkedRole} does not name the role it creates. AWS derives it from
     * {@code AWSServiceName}, so the same table the create reads gives the name, and the role
     * sits under the service-role path carrying the canonical principal.
     */
    @Test
    void iamNamesTheRoleAServiceLinkedCreateWouldMint() {
        assertEquals("arn:aws:iam::000000000000:role/aws-service-role/autoscaling.amazonaws.com/"
                        + "AWSServiceRoleForAutoScaling",
                resourceOf(builder, "Action=CreateServiceLinkedRole"
                        + "&AWSServiceName=autoscaling.amazonaws.com"));
        // A suffix is part of the name AWS mints, so it is part of the resource too.
        assertEquals("arn:aws:iam::000000000000:role/aws-service-role/autoscaling.amazonaws.com/"
                        + "AWSServiceRoleForAutoScaling_fleet",
                resourceOf(builder, "Action=CreateServiceLinkedRole"
                        + "&AWSServiceName=autoscaling.amazonaws.com&CustomSuffix=fleet"));
        // A principal the table does not carry keeps the derived name, which is what the create
        // mints for it: the two read the same table, so the check cannot name a different role.
        assertEquals("arn:aws:iam::000000000000:role/aws-service-role/es.amazonaws.com/"
                        + "AWSServiceRoleForEs",
                resourceOf(builder,
                        "Action=CreateServiceLinkedRole&AWSServiceName=es.amazonaws.com"));
    }

    /**
     * {@code GetAccessKeyLastUsed} is evaluated against a user, which is what the User Guide's own
     * {@code iam:*AccessKey*} example scopes to {@code user/${aws:username}}, but the request
     * names only the key, so the owner is resolved from it.
     */
    @Test
    void iamNamesTheUserOwningAnAccessKey() {
        String arn = "arn:aws:iam::000000000000:user/bob";
        IamService store = storeHolding("bob", "/", arn);
        when(store.findAccessKeyOwnerInThisAccount("AKIAEXAMPLE")).thenReturn(Optional.of("bob"));
        assertEquals(arn, resourceOf(backedBy(store),
                "Action=GetAccessKeyLastUsed&AccessKeyId=AKIAEXAMPLE"));
    }

    /**
     * The enforcement filter resolves the key's owner before the handler runs, so the resolver
     * must not migrate a pre-multi-account key into the caller's partition either: another
     * account asking about it, even one with a same-named user, gets the wildcard and leaves the
     * key where it was.
     */
    @Test
    void iamDoesNotLetAnotherAccountClaimALegacyAccessKeyThroughTheOwnerLookup() {
        String stranger = "111122223333";
        RequestContext context = mock(RequestContext.class);
        when(context.getAccountId()).thenReturn(stranger);
        @SuppressWarnings("unchecked")
        Instance<RequestContext> contexts = mock(Instance.class);
        when(contexts.get()).thenReturn(context);
        InMemoryStorage<String, AccessKey> raw = new InMemoryStorage<>();
        raw.put("AKIALEGACYEXAMPLE", new AccessKey("AKIALEGACYEXAMPLE", "secret", "worker"));
        IamService iam = new IamService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                new AccountAwareStorageBackend<>(raw, contexts, ACCOUNT), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new RegionResolver("us-east-1", ACCOUNT), false);
        iam.createUser("worker", "/");

        setFormBody("Action=GetAccessKeyLastUsed&AccessKeyId=AKIALEGACYEXAMPLE");
        assertEquals(List.of("*"), backedBy(iam).buildResources("iam", ctx, "us-east-1", stranger));
        assertTrue(raw.get("AKIALEGACYEXAMPLE").isPresent(), "the lookup must leave the key unmoved");
        assertTrue(raw.get(stranger + "/AKIALEGACYEXAMPLE").isEmpty(), "the lookup must not copy the key");
    }

    /**
     * A hardware MFA device's {@code SerialNumber} is a bare serial rather than an ARN, so there
     * is nothing to pass through and nothing a policy could be written against.
     */
    @Test
    void iamFallsBackToTheWildcardForASerialThatIsNotAnArn() {
        assertEquals("*", resourceOf(builder,
                "Action=TagMFADevice&SerialNumber=GAHT12345678"));
    }

    /**
     * An action whose resource the request does not name stays at {@code *}: the ones the
     * reference gives no resource type, and the ones that default to the calling user, whose
     * identity is the filter's to supply rather than this builder's.
     */
    @Test
    void iamFallsBackToTheWildcardWhenTheRequestNamesNoResource() {
        for (String form : List.of("Action=ListUsers", "Action=GetAccountSummary",
                "Action=CreateAccountAlias&AccountAlias=corp", "Action=GetUser",
                "Action=ChangePassword", "Action=SimulateCustomPolicy")) {
            assertEquals("*", resourceOf(builder, form), form);
        }
    }

    /**
     * The deletion task identifier carries the role's path and name, in the format the action's
     * reference publishes, which is the only thing left to name: the role itself is already gone
     * by the time the status is asked for.
     */
    @Test
    void iamNamesTheRoleADeletionTaskIdentifies() {
        assertEquals("arn:aws:iam::000000000000:role/aws-service-role/lex.amazonaws.com/"
                        + "AWSServiceRoleForLexBots",
                resourceOf(builder, "Action=GetServiceLinkedRoleDeletionStatus&DeletionTaskId="
                        + "task%2Faws-service-role%2Flex.amazonaws.com"
                        + "%2FAWSServiceRoleForLexBots%2F12345678-1234-1234-1234-123456789012"));
        // A malformed identifier names nothing rather than half a resource.
        for (String taskId : List.of("task%2Faws-service-role%2F", "nonsense", "task%2Fother%2Fx")) {
            assertEquals("*", resourceOf(builder,
                    "Action=GetServiceLinkedRoleDeletionStatus&DeletionTaskId=" + taskId), taskId);
        }
    }

    /**
     * An attach carries both a user and a policy ARN, and the reference gives it the user alone.
     * Taking the ARN the request happens to carry would scope it to the policy and let a deny on
     * the user pass, so the attaches stay out of the ARN-parameter table and this is what holds
     * them there: that table is read first, and an action in it never reaches the type the
     * vendored metadata gives.
     */
    @Test
    void iamNamesTheUserRatherThanThePolicyOnAnAttach() {
        String userArn = "arn:aws:iam::000000000000:user/bob";
        assertEquals(userArn,
                resourceOf(backedBy(storeHolding("bob", "/", userArn)),
                        "Action=AttachUserPolicy&UserName=bob"
                                + "&PolicyArn=arn%3Aaws%3Aiam%3A%3A000000000000%3Apolicy%2Fp"));
    }

    /**
     * The report {@code GenerateOrganizationsAccessReport} would produce. The reference spells the
     * type as {@code access-report/${EntityPath}}, so the entity path goes in whole: its slashes
     * are the organization's tree and there is no IAM path to read, which is why this does not go
     * through the minting the creates use.
     */
    @Test
    void iamNamesTheAccessReportAnOrganizationsEntityPathAsksFor() {
        assertEquals("arn:aws:iam::000000000000:access-report/"
                        + "o-a1b2c3d4e5/r-f6g7h8i9j0example/ou-rge0-awsabcde/123456789012",
                resourceOf(builder, "Action=GenerateOrganizationsAccessReport&EntityPath="
                        + "o-a1b2c3d4e5%2Fr-f6g7h8i9j0example%2Fou-rge0-awsabcde%2F123456789012"));
        // Without the path there is nothing to name, so the permissive answer stands.
        assertEquals("*", resourceOf(builder, "Action=GenerateOrganizationsAccessReport"));
    }

    /**
     * The resource a certificate-specific policy statement has to match. Without this the action
     * is evaluated against {@code *}, so a deny naming the certificate never applies.
     */
    @Test
    void iamNamesTheServerCertificateBeingActedOn() {
        String arn = "arn:aws:iam::000000000000:server-certificate/my-cert";
        setFormBody("Action=DeleteServerCertificate&ServerCertificateName=my-cert");
        assertEquals(arn, backedBy("my-cert", arn).build("iam", ctx, "us-east-1", "000000000000"));
    }

    @Test
    void iamNamesTheCertificateForEveryOperationThatTakesOne() {
        String arn = "arn:aws:iam::000000000000:server-certificate/c1";
        ResourceArnBuilder stored = backedBy("c1", arn);
        for (String action : List.of("GetServerCertificate", "UpdateServerCertificate",
                "DeleteServerCertificate", "TagServerCertificate", "UntagServerCertificate",
                "ListServerCertificateTags")) {
            setFormBody("Action=" + action + "&ServerCertificateName=c1");
            assertEquals(arn, stored.build("iam", ctx, "us-east-1", "000000000000"), action);
        }
        // The upload is the one that mints rather than reads: nothing is stored yet.
        setFormBody("Action=UploadServerCertificate&ServerCertificateName=c1");
        assertEquals(arn, builder.build("iam", ctx, "us-east-1", "000000000000"));
    }

    /**
     * The one invariant behind every way this has been got wrong: for an operation acting on a
     * certificate that exists, the ARN the permission check uses is that certificate's own stored
     * ARN -- whatever spelling named the operation, and whatever partition signed the request.
     *
     * <p>Enumerated rather than sampled, because each defect so far hid in a combination the
     * narrower tests did not reach: the mapping missing entirely, then {@code Operation} skipping
     * it, then the partition coming from the signing region. Nothing here derives an ARN, so a
     * regression has to show up as a mismatch.
     */
    @Test
    void iamChecksEveryRouteAndSigningRegionAgainstTheStoredArn() {
        for (String storedArn : List.of(
                "arn:aws:iam::000000000000:server-certificate/c1",
                "arn:aws-cn:iam::000000000000:server-certificate/team/c1",
                "arn:aws-us-gov:iam::000000000000:server-certificate/deep/nested/c1")) {
            ResourceArnBuilder stored = backedBy("c1", storedArn);
            for (String action : List.of("GetServerCertificate", "UpdateServerCertificate",
                    "DeleteServerCertificate", "TagServerCertificate", "UntagServerCertificate",
                    "ListServerCertificateTags")) {
                for (String spelling : List.of("Action", "Operation")) {
                    for (String region : List.of("us-east-1", "cn-north-1", "us-gov-west-1")) {
                        setFormBody(spelling + "=" + action + "&ServerCertificateName=c1");
                        assertEquals(storedArn,
                                stored.build("iam", ctx, region, "000000000000"),
                                action + " named by " + spelling + ", signed in " + region);
                    }
                }
            }
        }
    }

    /**
     * A rename is authorized against both names. The model requires the principal to be allowed on
     * the old and the new one, and the filter authorizes a request once per resource, so naming
     * both is what stops a rename into a name the caller may not write.
     */
    @Test
    void iamNamesBothTheOldAndTheNewNameOnARename() {
        String stored = "arn:aws:iam::000000000000:server-certificate/old";
        setFormBody("Action=UpdateServerCertificate&ServerCertificateName=old"
                + "&NewServerCertificateName=new");
        assertEquals(
                List.of(stored, "arn:aws:iam::000000000000:server-certificate/new"),
                backedBy("old", stored).buildResources("iam", ctx, "us-east-1", "000000000000"));
    }

    /** A move renames the path, so the destination path is named the same way. */
    @Test
    void iamNamesTheDestinationPathOnAMove() {
        String stored = "arn:aws:iam::000000000000:server-certificate/old";
        setFormBody("Action=UpdateServerCertificate&ServerCertificateName=old&NewPath=/team/");
        assertEquals(
                List.of(stored, "arn:aws:iam::000000000000:server-certificate/team/old"),
                backedBy("old", stored).buildResources("iam", ctx, "us-east-1", "000000000000"));
    }

    /**
     * An update that renames and moves nothing acts on one resource, so it names one. Repeating
     * the same ARN would make the request look like it touches two.
     */
    @Test
    void iamNamesOneResourceWhenAnUpdateChangesNeitherNameNorPath() {
        String stored = "arn:aws:iam::000000000000:server-certificate/old";
        setFormBody("Action=UpdateServerCertificate&ServerCertificateName=old");
        assertEquals(List.of(stored),
                backedBy("old", stored).buildResources("iam", ctx, "us-east-1", "000000000000"));
    }

    /**
     * The destination is built beside the stored ARN, so it keeps the certificate's partition. A
     * rename does not move a resource between partitions, and taking the partition from the
     * caller's signing region would name one that does not exist.
     */
    @Test
    void iamBuildsTheRenameDestinationInTheStoredPartition() {
        String stored = "arn:aws-cn:iam::000000000000:server-certificate/old";
        setFormBody("Action=UpdateServerCertificate&ServerCertificateName=old"
                + "&NewServerCertificateName=new");
        assertEquals(
                List.of(stored, "arn:aws-cn:iam::000000000000:server-certificate/new"),
                backedBy("old", stored).buildResources("iam", ctx, "us-east-1", "000000000000"));
    }

    /**
     * Nothing stored under that name means nothing to name: the operation fails as NoSuchEntity
     * regardless, and minting an ARN for an absent resource would hand a policy something to match.
     */
    @Test
    void iamFallsBackToTheWildcardWhenNoCertificateIsStored() {
        setFormBody("Action=DeleteServerCertificate&ServerCertificateName=absent");
        assertEquals("*", backedBy("other", "arn:aws:iam::000000000000:server-certificate/other")
                .build("iam", ctx, "us-east-1", "000000000000"));
    }

    /**
     * An upload names a certificate that does not exist yet, so its path has to come from the
     * request. Taking it from the store would resolve to the root path and let a policy scoped to
     * one path authorize an upload into another.
     */
    @Test
    void iamTakesTheUploadPathFromTheRequest() {
        setFormBody("Action=UploadServerCertificate&ServerCertificateName=c1&Path=/team/");
        assertEquals("arn:aws:iam::000000000000:server-certificate/team/c1",
                builder.build("iam", ctx, "us-east-1", "000000000000"));

        // Written without surrounding slashes, it still lands as a path segment.
        setFormBody("Action=UploadServerCertificate&ServerCertificateName=c1&Path=team");
        assertEquals("arn:aws:iam::000000000000:server-certificate/team/c1",
                builder.build("iam", ctx, "us-east-1", "000000000000"));

        // Omitted, it is the root path.
        setFormBody("Action=UploadServerCertificate&ServerCertificateName=c1");
        assertEquals("arn:aws:iam::000000000000:server-certificate/c1",
                builder.build("iam", ctx, "us-east-1", "000000000000"));
    }

    /**
     * IAM is global, so the ARN carries no region. An upload creates the certificate in the
     * caller's own partition, so that is the partition its ARN is minted in.
     */
    @Test
    void iamMintsAnUploadedCertificateArnInTheRequestPartition() {
        setFormBody("Action=UploadServerCertificate&ServerCertificateName=my-cert");
        assertEquals("arn:aws-cn:iam::000000000000:server-certificate/my-cert",
                builder.build("iam", ctx, "cn-north-1", "000000000000"));

        setFormBody("Action=UploadServerCertificate&ServerCertificateName=my-cert");
        assertEquals("arn:aws-us-gov:iam::000000000000:server-certificate/my-cert",
                builder.build("iam", ctx, "us-gov-west-1", "000000000000"));
    }

    /**
     * An existing certificate stays in the partition it was created in, so the check reads the
     * partition from its stored ARN. Deriving it from the caller's signing region instead would
     * name an ARN no resource has, and a deny on the certificate's real ARN would not match it.
     */
    @Test
    void iamKeepsTheStoredPartitionWhateverRegionSignedTheRequest() {
        String arn = "arn:aws:iam::000000000000:server-certificate/my-cert";
        ResourceArnBuilder stored = backedBy("my-cert", arn);

        setFormBody("Action=DeleteServerCertificate&ServerCertificateName=my-cert");
        assertEquals(arn, stored.build("iam", ctx, "cn-north-1", "000000000000"));

        setFormBody("Action=DeleteServerCertificate&ServerCertificateName=my-cert");
        assertEquals(arn, stored.build("iam", ctx, "us-gov-west-1", "000000000000"));
    }

    /**
     * ListServerCertificates names no certificate, and every other IAM action is still unmapped,
     * so both resolve to the wildcard rather than to a fabricated ARN.
     */
    @Test
    void iamFallsBackToTheWildcardWhenNoCertificateIsNamed() {
        setFormBody("Action=ListServerCertificates&PathPrefix=/team/");
        assertEquals("*", builder.build("iam", ctx, "us-east-1", "000000000000"));

        setFormBody("Action=DeleteUser&UserName=bob");
        assertEquals("*", builder.build("iam", ctx, "us-east-1", "000000000000"));

        setFormBody("Action=DeleteServerCertificate");
        assertEquals("*", builder.build("iam", ctx, "us-east-1", "000000000000"));
    }

    private void setJsonBody(String json) {
        contextProperties.clear();
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream in = new ByteArrayInputStream(bytes);
        when(ctx.getEntityStream()).thenReturn(in);
        doAnswer(inv -> {
            InputStream newIn = inv.getArgument(0);
            when(ctx.getEntityStream()).thenReturn(newIn);
            return null;
        }).when(ctx).setEntityStream(any(InputStream.class));
    }

    @Test
    void resourceGroupTargetsUseNamesOrArnsInTheRequestPartition() {
        for (String region : List.of("us-east-1", "cn-north-1", "us-gov-west-1")) {
            String arn = "arn:" + AwsRegions.partitionFor(region) + ":resource-groups:"
                    + region + ":" + ACCOUNT + ":group/pool";
            for (String selector : List.of("Name", "GroupName", "Group")) {
                when(uriInfo.getPath()).thenReturn(selector.equals("Name") ? "_rg/groups" : "_rg/get-group");
                setJsonBody("{\"" + selector + "\":\"pool\"}");
                assertEquals(List.of(arn), builder.buildResources("resource-groups", ctx, region, ACCOUNT));
            }
            setJsonBody("{\"Group\":\"" + arn + "\"}");
            assertEquals(List.of(arn), builder.buildResources("resource-groups", ctx, region, ACCOUNT));
        }
        setJsonBody("{}");
        assertEquals(List.of("*"), builder.buildResources("resource-groups", ctx, "us-east-1", ACCOUNT));
    }

    @Test
    void logStreamTargetsUseTheServingActionAndRequestPartition() {
        for (String action : List.of("logs:CreateLogStream", "logs:DeleteLogStream")) {
            for (String region : List.of("us-east-1", "cn-north-1", "us-gov-west-1")) {
                setJsonBody("{\"logGroupName\":\" /group \",\"logStreamName\":42}");
                when(ctx.getHeaderString("X-Amz-Target")).thenReturn("Logs_20140328.DeleteLogGroup");
                assertEquals(List.of("arn:" + AwsRegions.partitionFor(region)
                                + ":logs:" + region + ":123456789012:log-group: /group :log-stream:42"),
                        builder.buildLogResources(action, ctx, region, "123456789012", AwsRegions.partitionFor(region)));
            }
        }
    }

    @Test
    void logGroupTargetsUseTheServingActionAndRequestPartition() {
        for (String action : List.of("logs:CreateLogGroup", "logs:DeleteLogGroup", "logs:PutRetentionPolicy",
                "logs:DeleteRetentionPolicy")) {
            for (String region : List.of("us-east-1", "cn-north-1", "us-gov-west-1")) {
                setJsonBody("{\"logGroupName\":42}");
                when(ctx.getHeaderString("X-Amz-Target")).thenReturn("Logs_20140328.DeleteLogStream");
                assertEquals(List.of("arn:" + AwsRegions.partitionFor(region)
                                + ":logs:" + region + ":123456789012:log-group:42:*"),
                        builder.buildLogResources(action, ctx, region, "123456789012", AwsRegions.partitionFor(region)));
            }
        }
    }

    @Test
    void directLogTagTargetsUseBareArnAndServingAction() {
        for (String action : List.of("logs:TagResource", "logs:UntagResource", "logs:ListTagsForResource")) {
            for (String region : List.of("us-east-1", "cn-north-1", "us-gov-west-1", "custom-region-1")) {
                String partition = AwsRegions.partitionFor(region);
                String arn = "arn:" + partition + ":logs:" + region + ":" + ACCOUNT + ":log-group:group";
                setJsonBody("{\"resourceArn\":\"" + arn + "\"}");
                when(ctx.getHeaderString("X-Amz-Target")).thenReturn("Logs_20140328.DeleteLogGroup");
                assertEquals(List.of(arn), builder.buildLogResources(action, ctx, region, ACCOUNT, partition));
            }
        }
    }

    @Test
    void excludedLogsActionsKeepWildcardResources() {
        for (String action : List.of("logs:PutLogEvents")) {
            setJsonBody("{\"logGroupName\":\"group\",\"logStreamName\":\"stream\"}");
            assertEquals(List.of("*"), builder.buildLogResources(action, ctx, "us-east-1", ACCOUNT, "aws"));
        }
    }

    // ── STS ──────────────────────────────────────────────────────────────────────

    @Test
    void stsAssumeRoleIsAuthorizedAgainstTheRoleItNames() {
        setFormBody("Action=AssumeRole&Version=2011-06-15"
                + "&RoleArn=arn%3Aaws%3Aiam%3A%3A729951873967%3Arole%2FAdmin&RoleSessionName=robin");
        String arn = sts("sts:AssumeRole");
        assertEquals("arn:aws:iam::729951873967:role/Admin", arn);
    }

    @Test
    void stsAssumeRoleIgnoresARoleArnInTheUrlOfAFormPost() {
        // The handler acts on the body's RoleArn, so authorization must check that one, never a
        // decoy in the query string.
        queryParams.add("RoleArn", "arn:aws:iam::729951873967:role/Allowed");
        setFormBody("Action=AssumeRole&Version=2011-06-15"
                + "&RoleArn=arn%3Aaws%3Aiam%3A%3A729951873967%3Arole%2FAdmin&RoleSessionName=robin");
        String arn = sts("sts:AssumeRole");
        assertEquals("arn:aws:iam::729951873967:role/Admin", arn);
    }

    @Test
    void stsReadsRoleArnOnlyForAssumeRole() {
        // GetFederationToken never acts on a role, so a RoleArn added to it must not let a policy
        // scoped to that role allow it.
        setFormBody("Action=GetFederationToken&Version=2011-06-15&Name=robin"
                + "&RoleArn=arn%3Aaws%3Aiam%3A%3A729951873967%3Arole%2FAdmin");
        assertEquals("*", sts("sts:GetFederationToken"));
    }

    @Test
    void stsMalformedRoleArnIsAuthorizedAsNoRole() {
        // Read as absent, so the request reaches the form reader, which refuses it as it does today.
        setFormBody("Action=AssumeRole&Version=2011-06-15&RoleArn=arn%3Aaws%ZZ&RoleSessionName=robin");
        assertEquals("*", sts("sts:AssumeRole"));
    }

    @Test
    void stsRoleArnIsCheckedAsTheHandlerReadsIt() {
        // The handler does not trim, so neither does the check.
        setFormBody("Action=AssumeRole&Version=2011-06-15"
                + "&RoleArn=+arn%3Aaws%3Aiam%3A%3A729951873967%3Arole%2FAdmin&RoleSessionName=robin");
        assertEquals(" arn:aws:iam::729951873967:role/Admin", sts("sts:AssumeRole"));
    }

    private String sts(String action) {
        return builder.buildStsResources(action, ctx).getFirst();
    }

    @Test
    void sqsBuildsArnFromFormBodyQueueUrl() {
        // The handler acts on the body's QueueUrl, so a decoy in the query string must not be checked.
        queryParams.add("QueueUrl", "http://localhost:4566/000000000000/allowed");
        setFormBody("Action=SendMessage&QueueUrl=http%3A%2F%2Flocalhost%3A4566%2F000000000000%2Forders&MessageBody=x");
        String arn = builder.build("sqs", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:sqs:us-east-1:000000000000:orders", arn);
    }


    // ── DynamoDB ─────────────────────────────────────────────────────────────────

    @Test
    void dynamoDbBuildsArnFromShortTableName() {
        setJsonBody("{\"TableName\":\"FgacTable\"}");
        String arn = builder.build("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:dynamodb:us-east-1:000000000000:table/FgacTable", arn);
    }

    /**
     * Pass-through used to be a {@code startsWith("arn:aws:dynamodb:")} probe, so a table ARN from
     * any other partition was not recognised as an ARN at all and got rebuilt as
     * {@code table/arn:aws-cn:dynamodb:...}, a resource name that matches no policy.
     */
    @Test
    void dynamoDbReturnsExactArnForAnyPartition() {
        for (String fullArn : List.of(
                "arn:aws-us-gov:dynamodb:us-gov-west-1:000000000000:table/FgacTable",
                "arn:aws-cn:dynamodb:cn-north-1:000000000000:table/FgacTable")) {
            setJsonBody("{\"TableName\":\"" + fullArn + "\"}");
            assertEquals(fullArn, builder.build("dynamodb", ctx, "us-east-1", "000000000000"));
        }
    }

    /** A bare name that merely looks ARN-ish is still a name, not an ARN. */
    @Test
    void dynamoDbTreatsAnIncompleteArnAsATableName() {
        setJsonBody("{\"TableName\":\"arn:aws:dynamodb:us-east-1\"}");
        assertEquals("arn:aws:dynamodb:us-east-1:000000000000:table/arn:aws:dynamodb:us-east-1",
                builder.build("dynamodb", ctx, "us-east-1", "000000000000"));
    }

    @Test
    void dynamoDbReturnsExactArnIfTableNameIsAlreadyArn() {
        String fullArn = "arn:aws:dynamodb:us-east-1:000000000000:table/FgacTable";
        setJsonBody("{\"TableName\":\"" + fullArn + "\"}");
        String arn = builder.build("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals(fullArn, arn);
    }

    @Test
    void dynamoDbBuildsArnFromResourceArn() {
        String tagArn = "arn:aws:dynamodb:us-east-1:000000000000:table/FgacTable";
        setJsonBody("{\"ResourceArn\":\"" + tagArn + "\",\"Tags\":[{\"Key\":\"env\",\"Value\":\"dev\"}]}");
        String arn = builder.build("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals(tagArn, arn);
    }

    @Test
    void dynamoDbBuildsArnFromTableArn() {
        String exportArn = "arn:aws:dynamodb:us-east-1:000000000000:table/ExportTable";
        setJsonBody("{\"TableArn\":\"" + exportArn + "\",\"S3Bucket\":\"my-bucket\"}");
        String arn = builder.build("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals(exportArn, arn);
    }

    @Test
    void dynamoDbBuildsArnFromStreamArn() {
        String streamArn = "arn:aws:dynamodb:us-east-1:000000000000:table/FgacTable/stream/2026-09-01T00:00:00.000";
        setJsonBody("{\"StreamArn\":\"" + streamArn + "\"}");
        String arn = builder.build("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals(streamArn, arn);
    }

    @Test
    void dynamoDbBuildsArnFromExportArn() {
        String exportArn = "arn:aws:dynamodb:us-east-1:000000000000:table/FgacTable/export/01693526400000-abcd";
        setJsonBody("{\"ExportArn\":\"" + exportArn + "\"}");
        String arn = builder.build("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals(exportArn, arn);
    }

    @Test
    void dynamoDbBuildsArnFromTheSourceOfAPointInTimeRestore() {
        setJsonBody("{\"SourceTableName\":\"Music\",\"TargetTableName\":\"MusicCopy\"}");
        assertEquals("arn:aws:dynamodb:us-east-1:000000000000:table/Music",
                builder.build("dynamodb", ctx, "us-east-1", "000000000000"));
        String sourceArn = "arn:aws:dynamodb:eu-west-2:000000000000:table/Music";
        setJsonBody("{\"SourceTableArn\":\"" + sourceArn + "\",\"TargetTableName\":\"MusicCopy\"}");
        assertEquals(sourceArn, builder.build("dynamodb", ctx, "us-east-1", "000000000000"));
    }

    @Test
    void dynamoDbBuildsArnFromSingleTableBatchRequest() {
        setJsonBody("{\"RequestItems\":{\"MyTable\":{\"Keys\":[]}}}");
        String arn = builder.build("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:dynamodb:us-east-1:000000000000:table/MyTable", arn);
    }

    @Test
    void dynamoDbBuildsArnsFromMultiTableBatchRequest() {
        setJsonBody("{\"RequestItems\":{\"TableA\":{\"Keys\":[]},\"TableB\":{\"Keys\":[]}}}");
        List<String> arns = builder.buildResources("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals(List.of(
                "arn:aws:dynamodb:us-east-1:000000000000:table/TableA",
                "arn:aws:dynamodb:us-east-1:000000000000:table/TableB"
        ), arns);
    }

    @Test
    void dynamoDbBuildsArnFromSingleTableTransactRequest() {
        setJsonBody("{\"TransactItems\":[{\"Put\":{\"TableName\":\"MyTable\"}},{\"Delete\":{\"TableName\":\"MyTable\"}}]}");
        String arn = builder.build("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:dynamodb:us-east-1:000000000000:table/MyTable", arn);
    }

    @Test
    void dynamoDbBuildsArnsFromMultiTableTransactRequest() {
        setJsonBody("{\"TransactItems\":[{\"Put\":{\"TableName\":\"TableA\"}},{\"Delete\":{\"TableName\":\"TableB\"}},{\"Get\":{\"TableName\":\"TableA\"}}]}");
        List<String> arns = builder.buildResources("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals(List.of(
                "arn:aws:dynamodb:us-east-1:000000000000:table/TableA",
                "arn:aws:dynamodb:us-east-1:000000000000:table/TableB"
        ), arns);
    }

    @Test
    void dynamoDbBuildsArnFromPartiQLStatement() {
        setJsonBody("{\"Statement\":\"SELECT * FROM \\\"UsersTable\\\" WHERE id = '1'\"}");
        List<String> arns = builder.buildResources("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals(List.of("arn:aws:dynamodb:us-east-1:000000000000:table/UsersTable"), arns);

        setJsonBody("{\"Statement\":\"INSERT INTO OrdersTable VALUE {'id': '1'}\"}");
        arns = builder.buildResources("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals(List.of("arn:aws:dynamodb:us-east-1:000000000000:table/OrdersTable"), arns);

        setJsonBody("{\"Statement\":\"UPDATE \\\"ProductsTable\\\" SET price=10 WHERE id='1'\"}");
        arns = builder.buildResources("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals(List.of("arn:aws:dynamodb:us-east-1:000000000000:table/ProductsTable"), arns);

        setJsonBody("{\"Statement\":\"DELETE FROM \\\"LogsTable\\\" WHERE id='1'\"}");
        arns = builder.buildResources("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals(List.of("arn:aws:dynamodb:us-east-1:000000000000:table/LogsTable"), arns);

        setJsonBody("{\"Statement\":\"SELECT * FROM \\\"Table With Spaces & Special:Chars\\\" WHERE id = '1'\"}");
        arns = builder.buildResources("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals(List.of("arn:aws:dynamodb:us-east-1:000000000000:table/Table With Spaces & Special:Chars"), arns);

        setJsonBody("{\"Statement\":\"SELECT * FROM 'Table.With.Single.Quotes#1' WHERE id = '1'\"}");
        arns = builder.buildResources("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals(List.of("arn:aws:dynamodb:us-east-1:000000000000:table/Table.With.Single.Quotes#1"), arns);

        setJsonBody("{\"Statement\":\"SELECT \\\"FROM TableEvil\\\" FROM \\\"TableA\\\" WHERE pk = 1\"}");
        arns = builder.buildResources("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals(List.of("arn:aws:dynamodb:us-east-1:000000000000:table/TableA"), arns);
    }

    @Test
    void dynamoDbBuildsArnsFromPartiQLBatchStatements() {
        setJsonBody("{\"Statements\":[{\"Statement\":\"SELECT * FROM \\\"TableA\\\" WHERE id = '1'\"},{\"Statement\":\"SELECT * FROM TableB WHERE id = '2'\"}]}");
        List<String> arns = builder.buildResources("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals(List.of(
                "arn:aws:dynamodb:us-east-1:000000000000:table/TableA",
                "arn:aws:dynamodb:us-east-1:000000000000:table/TableB"
        ), arns);
    }

    @Test
    void dynamoDbBuildsArnsFromPartiQLTransactStatements() {
        setJsonBody("{\"TransactStatements\":[{\"Statement\":\"INSERT INTO \\\"TableA\\\" VALUE {'id': '1'}\"},{\"Statement\":\"UPDATE \\\"TableB\\\" SET x=1 WHERE id='2'\"}]}");
        List<String> arns = builder.buildResources("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals(List.of(
                "arn:aws:dynamodb:us-east-1:000000000000:table/TableA",
                "arn:aws:dynamodb:us-east-1:000000000000:table/TableB"
        ), arns);
    }

    @Test
    void dynamoDbBuildsArnFromTableCreationParameters() {
        setJsonBody("{\"TableCreationParameters\":{\"TableName\":\"ImportTarget\"},\"InputFormat\":\"DYNAMODB_JSON\"}");
        String arn = builder.build("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:dynamodb:us-east-1:000000000000:table/ImportTarget", arn);
    }

    @Test
    void dynamoDbReturnsWildcardWhenNoTableSpecified() {
        setJsonBody("{}");
        String arn = builder.build("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals("*", arn);
    }

    @Test
    void entityStreamRemainsReadableAfterResourceArnBuilding() throws IOException {
        String json = "{\"TableName\":\"FgacTable\",\"Item\":{\"PK\":{\"S\":\"USER_1\"}}}";
        setJsonBody(json);

        String arn = builder.build("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:dynamodb:us-east-1:000000000000:table/FgacTable", arn);

        InputStream entityStream = ctx.getEntityStream();
        assertNotNull(entityStream);
        byte[] readBack = entityStream.readAllBytes();
        assertEquals(json, new String(readBack, StandardCharsets.UTF_8));
    }

    // ── Kinesis ──────────────────────────────────────────────────────────────────

    @Test
    void kinesisBuildsArnFromStreamName() {
        setJsonBody("{\"StreamName\":\"order-events\"}");
        String arn = builder.build("kinesis", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:kinesis:us-east-1:000000000000:stream/order-events", arn);
    }

    @Test
    void kinesisBuildsArnFromStreamArn() {
        String fullArn = "arn:aws:kinesis:us-east-1:000000000000:stream/order-events";
        setJsonBody("{\"StreamARN\":\"" + fullArn + "\"}");
        String arn = builder.build("kinesis", ctx, "us-east-1", "000000000000");
        assertEquals(fullArn, arn);
    }

    // ── Secrets Manager ──────────────────────────────────────────────────────────

    @Test
    void secretsManagerBuildsArnFromSecretId() {
        setJsonBody("{\"SecretId\":\"app/prod/database\"}");
        assertEquals(List.of("arn:aws:secretsmanager:us-east-1:000000000000:secret:app/prod/database"),
                builder.buildSecretsManagerResources("secretsmanager:GetSecretValue", ctx, "us-east-1",
                        "000000000000", "aws"));
    }

    @Test
    void secretsManagerResolvesASecretNamedByItsNameToItsStoredArn() {
        String storedArn = "arn:aws:secretsmanager:us-east-1:111122223333:secret:app/core-AbC123";
        SecretsManagerService secrets = mock(SecretsManagerService.class);
        when(secrets.findArn("111122223333", "app/core", "us-east-1")).thenReturn(Optional.of(storedArn));
        setJsonBody("{\"SecretId\":\"app/core\"}");

        List<String> arns = backedBy(secrets).buildSecretsManagerResources("secretsmanager:GetSecretValue", ctx,
                "us-east-1", "111122223333", "aws");

        assertEquals(List.of(storedArn), arns);
    }

    @Test
    void secretsManagerKeepsTheNameOfASecretThatIsNotStored() {
        SecretsManagerService secrets = mock(SecretsManagerService.class);
        when(secrets.findArn(ACCOUNT, "app/missing", "us-east-1")).thenReturn(Optional.empty());
        setJsonBody("{\"SecretId\":\"app/missing\"}");

        List<String> arns = backedBy(secrets).buildSecretsManagerResources("secretsmanager:GetSecretValue", ctx,
                "us-east-1", ACCOUNT, "aws");

        assertEquals(List.of("arn:aws:secretsmanager:us-east-1:000000000000:secret:app/missing"), arns);
    }

    @Test
    void secretsManagerResolvesAPartialArnToTheStoredArnAndKeepsAnArnThatIsNotStored() {
        String partialArn = "arn:aws:secretsmanager:us-east-1:000000000000:secret:app/core";
        String storedArn = partialArn + "-AbC123";
        String missingArn = "arn:aws:secretsmanager:us-east-1:000000000000:secret:app/missing";
        SecretsManagerService secrets = mock(SecretsManagerService.class);
        when(secrets.findArn(ACCOUNT, partialArn, "us-east-1")).thenReturn(Optional.of(storedArn));
        when(secrets.findArn(ACCOUNT, missingArn, "us-east-1")).thenReturn(Optional.empty());
        ResourceArnBuilder backed = backedBy(secrets);

        setJsonBody("{\"SecretId\":\"" + partialArn + "\"}");
        assertEquals(List.of(storedArn), backed.buildSecretsManagerResources("secretsmanager:GetSecretValue", ctx,
                "us-east-1", ACCOUNT, "aws"));
        setJsonBody("{\"SecretId\":\"" + missingArn + "\"}");
        assertEquals(List.of(missingArn), backed.buildSecretsManagerResources("secretsmanager:GetSecretValue", ctx,
                "us-east-1", ACCOUNT, "aws"));
    }

    @Test
    void secretsManagerCreateSecretBuildsArnFromNameWithAnUnassignedSuffix() {
        setJsonBody("{\"Name\":\"app/core\",\"SecretString\":\"x\"}");
        assertEquals(List.of("arn:aws:secretsmanager:us-east-1:000000000000:secret:app/core-??????"),
                builder.buildSecretsManagerResources("secretsmanager:CreateSecret", ctx, "us-east-1", ACCOUNT,
                        "aws"));
    }

    @Test
    void secretsManagerTargetsTheFieldTheServingOperationReads() {
        SecretsManagerService secrets = mock(SecretsManagerService.class);
        when(secrets.findArn(ACCOUNT, "app/existing", "us-east-1"))
                .thenReturn(Optional.of("arn:aws:secretsmanager:us-east-1:000000000000:secret:app/existing-AbC123"));
        ResourceArnBuilder backed = backedBy(secrets);

        setJsonBody("{\"Name\":\"app/new\",\"SecretId\":\"app/existing\",\"SecretString\":\"x\"}");
        assertEquals(List.of("arn:aws:secretsmanager:us-east-1:000000000000:secret:app/new-??????"),
                backed.buildSecretsManagerResources("secretsmanager:CreateSecret", ctx, "us-east-1", ACCOUNT, "aws"));
        setJsonBody("{\"Name\":\"app/new\"}");
        assertEquals(List.of("arn:aws:secretsmanager:us-east-1:000000000000:secret:*"),
                backed.buildSecretsManagerResources("secretsmanager:GetSecretValue", ctx, "us-east-1", ACCOUNT,
                        "aws"));
    }

    @Test
    void secretsManagerMintsArnsInTheRequestPartition() {
        setJsonBody("{\"Name\":\"app/core\"}");
        assertEquals(List.of("arn:aws-cn:secretsmanager:polygondwanaland-west-1:000000000000:secret:app/core-??????"),
                builder.buildSecretsManagerResources("secretsmanager:CreateSecret", ctx, "polygondwanaland-west-1",
                        ACCOUNT, "aws-cn"));
        setJsonBody("{\"SecretId\":\"app/core\"}");
        assertEquals(List.of("arn:aws-us-gov:secretsmanager:polygondwanaland-west-1:000000000000:secret:app/core"),
                builder.buildSecretsManagerResources("secretsmanager:GetSecretValue", ctx, "polygondwanaland-west-1",
                        ACCOUNT, "aws-us-gov"));
    }

    // ── KMS ──────────────────────────────────────────────────────────────────────

    private static final String KMS_KEY_ID = "1234abcd-12ab-34cd-56ef-1234567890ab";
    private static final String KMS_KEY_ARN = "arn:aws:kms:us-east-1:000000000000:key/" + KMS_KEY_ID;
    private static final String KMS_OTHER_KEY_ARN = "arn:aws:kms:us-east-1:000000000000:key/5678efab-34cd-12ab-90ef-1234567890ab";
    private static final String KMS_ALIAS_ARN = "arn:aws:kms:us-east-1:000000000000:alias/app";

    /** A builder reading {@code kms}, for a key named by an id, an alias or an ARN. */
    private static ResourceArnBuilder backedBy(KmsService kms) {
        @SuppressWarnings("unchecked")
        Instance<KmsService> instance = mock(Instance.class);
        when(instance.get()).thenReturn(kms);
        return new ResourceArnBuilder(new ObjectMapper(), null, null, null, null, instance);
    }

    /**
     * The gap issue 6256 describes. KMS is a JSON protocol on {@code /} with the key in the body,
     * so reading a {@code /keys/<id>} path segment named nothing and every call was evaluated
     * against {@code key/*}: an {@code Allow} scoped to one key ARN never matched, and a
     * {@code Deny} scoped to one never fired. A key-scoped operation is now evaluated against the
     * key its request names.
     */
    @Test
    void kmsNamesTheKeyAKeyScopedOperationActsOn() {
        KmsService kms = mock(KmsService.class);
        when(kms.findKeyArn(any(), any())).thenReturn(Optional.empty());
        when(kms.findKeyArn(KMS_KEY_ID, "us-east-1")).thenReturn(Optional.of(KMS_KEY_ARN));
        ResourceArnBuilder backed = backedBy(kms);
        for (String action : List.of("kms:Encrypt", "kms:Decrypt", "kms:GenerateDataKey",
                "kms:GenerateDataKeyWithoutPlaintext", "kms:DescribeKey", "kms:GetPublicKey",
                "kms:ScheduleKeyDeletion", "kms:CancelKeyDeletion", "kms:CreateGrant", "kms:ListGrants",
                "kms:RevokeGrant", "kms:Sign", "kms:Verify", "kms:GenerateMac", "kms:VerifyMac",
                "kms:TagResource", "kms:UntagResource", "kms:ListResourceTags", "kms:GetKeyPolicy",
                "kms:PutKeyPolicy", "kms:ListKeyPolicies", "kms:UpdateKeyDescription",
                "kms:EnableKey", "kms:DisableKey", "kms:EnableKeyRotation", "kms:DisableKeyRotation",
                "kms:GetKeyRotationStatus", "kms:RotateKeyOnDemand", "kms:GetParametersForImport",
                "kms:ImportKeyMaterial", "kms:DeleteImportedKeyMaterial", "kms:ReplicateKey",
                "kms:RetireGrant")) {
            setJsonBody("{\"KeyId\":\"" + KMS_KEY_ID + "\"}");
            assertEquals(List.of(KMS_KEY_ARN),
                    backed.buildKmsResources(action, ctx, "us-east-1", ACCOUNT, "aws"), action);
        }
    }

    /** A key named by an alias is authorized against the key it points at, as the handler resolves it. */
    @Test
    void kmsResolvesAnAliasToTheKeyItPointsAt() {
        KmsService kms = mock(KmsService.class);
        when(kms.findKeyArn("alias/app", "us-east-1")).thenReturn(Optional.of(KMS_KEY_ARN));
        when(kms.findKeyArn(KMS_ALIAS_ARN, "us-east-1")).thenReturn(Optional.of(KMS_KEY_ARN));
        ResourceArnBuilder backed = backedBy(kms);

        setJsonBody("{\"KeyId\":\"alias/app\"}");
        assertEquals(List.of(KMS_KEY_ARN),
                backed.buildKmsResources("kms:Encrypt", ctx, "us-east-1", ACCOUNT, "aws"));
        setJsonBody("{\"KeyId\":\"" + KMS_ALIAS_ARN + "\"}");
        assertEquals(List.of(KMS_KEY_ARN),
                backed.buildKmsResources("kms:Encrypt", ctx, "us-east-1", ACCOUNT, "aws"));
    }

    /**
     * The operations the KMS permissions reference gives no resource, which it also requires a
     * policy to name {@code *} for, stay at the wildcard; a key-scoped operation that names no
     * key keeps the permissive fallback rather than being refused here, since the handler
     * refuses it with its own error.
     */
    @Test
    void kmsLeavesTheActionsThatNameNoKeyAtTheirOwnResources() {
        ResourceArnBuilder backed = backedBy(mock(KmsService.class));
        for (String action : List.of("kms:CreateKey", "kms:GenerateRandom", "kms:ListKeys",
                "kms:ListAliases", "kms:ListRetirableGrants")) {
            setJsonBody("{\"KeyId\":\"" + KMS_KEY_ID + "\"}");
            assertEquals(List.of("*"),
                    backed.buildKmsResources(action, ctx, "us-east-1", ACCOUNT, "aws"), action);
        }
        setJsonBody("{}");
        assertEquals(List.of("arn:aws:kms:us-east-1:000000000000:key/*"),
                backed.buildKmsResources("kms:RetireGrant", ctx, "us-east-1", ACCOUNT, "aws"));
        assertEquals(List.of("arn:aws-cn:kms:cn-north-1:000000000000:key/*"),
                backed.buildKmsResources("kms:RetireGrant", ctx, "cn-north-1", ACCOUNT, "aws-cn"));
    }

    /**
     * A request with no body at all keeps the action's own resources: the operations the reference
     * gives no resource stay at {@code *}, and a key-scoped one falls back to every key the
     * account holds rather than being refused here.
     */
    @Test
    void kmsKeepsEachActionsOwnResourcesForARequestWithNoBody() {
        ResourceArnBuilder backed = backedBy(mock(KmsService.class));
        assertEquals(List.of("*"),
                backed.buildKmsResources("kms:CreateKey", ctx, "us-east-1", ACCOUNT, "aws"));
        assertEquals(List.of("arn:aws:kms:us-east-1:000000000000:key/*"),
                backed.buildKmsResources("kms:Encrypt", ctx, "us-east-1", ACCOUNT, "aws"));
    }

    /**
     * A key ARN the account holds nothing of is kept as the caller spelled it, a bare key id is
     * minted in the request's partition, and an alias that resolves to nothing names every key:
     * the operation fails before a policy matters.
     */
    @Test
    void kmsMintsKeyArnsInTheRequestPartitionAndFallsBackForTheUnresolvable() {
        KmsService kms = mock(KmsService.class);
        when(kms.findKeyArn(any(), any())).thenReturn(Optional.empty());
        ResourceArnBuilder backed = backedBy(kms);

        setJsonBody("{\"KeyId\":\"" + KMS_KEY_ARN + "\"}");
        assertEquals(List.of(KMS_KEY_ARN),
                backed.buildKmsResources("kms:Encrypt", ctx, "us-east-1", ACCOUNT, "aws"));

        setJsonBody("{\"KeyId\":\"" + KMS_KEY_ID + "\"}");
        assertEquals(List.of("arn:aws-cn:kms:cn-north-1:000000000000:key/" + KMS_KEY_ID),
                backed.buildKmsResources("kms:Encrypt", ctx, "cn-north-1", ACCOUNT, "aws-cn"));

        setJsonBody("{\"KeyId\":\"alias/gone\"}");
        assertEquals(List.of("arn:aws:kms:us-east-1:000000000000:key/*"),
                backed.buildKmsResources("kms:Encrypt", ctx, "us-east-1", ACCOUNT, "aws"));
    }

    /**
     * A {@code Decrypt} names its key in an optional {@code KeyId}; without one the key is the
     * one that encrypted the {@code CiphertextBlob}, read the same way the handler reads it.
     */
    @Test
    void kmsDecryptResolvesTheKeyFromTheCiphertextItNames() {
        byte[] blob = "ciphertext".getBytes(StandardCharsets.UTF_8);
        String encoded = Base64.getEncoder().encodeToString(blob);
        KmsService kms = mock(KmsService.class);
        when(kms.findKeyArn(any(), any())).thenReturn(Optional.empty());
        when(kms.decryptToKeyArn(blob, "us-east-1")).thenReturn(KMS_KEY_ARN);
        ResourceArnBuilder backed = backedBy(kms);

        setJsonBody("{\"CiphertextBlob\":\"" + encoded + "\"}");
        assertEquals(List.of(KMS_KEY_ARN),
                backed.buildKmsResources("kms:Decrypt", ctx, "us-east-1", ACCOUNT, "aws"));

        when(kms.findKeyArn("alias/app", "us-east-1")).thenReturn(Optional.of(KMS_OTHER_KEY_ARN));
        setJsonBody("{\"KeyId\":\"alias/app\",\"CiphertextBlob\":\"" + encoded + "\"}");
        assertEquals(List.of(KMS_OTHER_KEY_ARN),
                backed.buildKmsResources("kms:Decrypt", ctx, "us-east-1", ACCOUNT, "aws"));

        KmsService unresolvable = mock(KmsService.class);
        when(unresolvable.findKeyArn(any(), any())).thenReturn(Optional.empty());
        when(unresolvable.decryptToKeyArn(any(), any())).thenReturn(null);
        setJsonBody("{\"CiphertextBlob\":\"" + encoded + "\"}");
        assertEquals(List.of("arn:aws:kms:us-east-1:000000000000:key/*"),
                backedBy(unresolvable).buildKmsResources("kms:Decrypt", ctx, "us-east-1", ACCOUNT, "aws"));

        setJsonBody("{\"CiphertextBlob\":629}");
        assertEquals(List.of("arn:aws:kms:us-east-1:000000000000:key/*"),
                backed.buildKmsResources("kms:Decrypt", ctx, "us-east-1", ACCOUNT, "aws"));

        setJsonBody("{\"CiphertextBlob\":{\"KeyId\":\"x\"}}");
        assertEquals(List.of("arn:aws:kms:us-east-1:000000000000:key/*"),
                backed.buildKmsResources("kms:Decrypt", ctx, "us-east-1", ACCOUNT, "aws"));

        setJsonBody("{\"CiphertextBlob\":[\"aGk=\"]}");
        assertEquals(List.of("arn:aws:kms:us-east-1:000000000000:key/*"),
                backed.buildKmsResources("kms:Decrypt", ctx, "us-east-1", ACCOUNT, "aws"));
    }

    /**
     * The alias operations name their resources as the alias permissions page gives them:
     * {@code CreateAlias} the alias and the key, {@code UpdateAlias} the alias, the key it points
     * at and the key it will point at, and {@code DeleteAlias} the alias and the key it points
     * at. A request naming no alias AWS would accept the name of names nothing.
     */
    @Test
    void kmsAliasOperationsNameTheAliasAndTheKeysTheyTouch() {
        KmsService kms = mock(KmsService.class);
        when(kms.findKeyArn(any(), any())).thenReturn(Optional.empty());
        when(kms.findKeyArn(KMS_KEY_ID, "us-east-1")).thenReturn(Optional.of(KMS_KEY_ARN));
        when(kms.findKeyArn("alias/app", "us-east-1")).thenReturn(Optional.of(KMS_OTHER_KEY_ARN));
        ResourceArnBuilder backed = backedBy(kms);

        setJsonBody("{\"AliasName\":\"alias/app\",\"TargetKeyId\":\"" + KMS_KEY_ID + "\"}");
        assertEquals(List.of(KMS_ALIAS_ARN, KMS_KEY_ARN),
                backed.buildKmsResources("kms:CreateAlias", ctx, "us-east-1", ACCOUNT, "aws"));

        setJsonBody("{\"AliasName\":\"alias/app\",\"TargetKeyId\":\"" + KMS_KEY_ID + "\"}");
        assertEquals(List.of(KMS_ALIAS_ARN, KMS_OTHER_KEY_ARN, KMS_KEY_ARN),
                backed.buildKmsResources("kms:UpdateAlias", ctx, "us-east-1", ACCOUNT, "aws"));

        // A retarget onto the key the alias already points at names that key once.
        when(kms.findKeyArn("alias/app", "us-east-1")).thenReturn(Optional.of(KMS_KEY_ARN));
        setJsonBody("{\"AliasName\":\"alias/app\",\"TargetKeyId\":\"" + KMS_KEY_ID + "\"}");
        assertEquals(List.of(KMS_ALIAS_ARN, KMS_KEY_ARN),
                backed.buildKmsResources("kms:UpdateAlias", ctx, "us-east-1", ACCOUNT, "aws"));

        when(kms.findKeyArn("alias/app", "us-east-1")).thenReturn(Optional.of(KMS_OTHER_KEY_ARN));
        setJsonBody("{\"AliasName\":\"alias/app\"}");
        assertEquals(List.of(KMS_ALIAS_ARN, KMS_OTHER_KEY_ARN),
                backed.buildKmsResources("kms:DeleteAlias", ctx, "us-east-1", ACCOUNT, "aws"));

        // A name AWS would refuse contributes no alias resource, and a request naming no alias
        // at all names nothing but the wildcard.
        when(kms.findKeyArn(any(), any())).thenReturn(Optional.empty());
        setJsonBody("{\"AliasName\":\"app\",\"TargetKeyId\":\"" + KMS_KEY_ID + "\"}");
        assertEquals(List.of(KMS_KEY_ARN),
                backed.buildKmsResources("kms:CreateAlias", ctx, "us-east-1", ACCOUNT, "aws"));
        setJsonBody("{}");
        assertEquals(List.of("*"),
                backed.buildKmsResources("kms:DeleteAlias", ctx, "us-east-1", ACCOUNT, "aws"));
    }

    /**
     * A {@code ReEncrypt} acts on two keys, and the filter authorizes a request once per
     * resource, so both are named: the source the ciphertext or its optional {@code SourceKeyId}
     * resolves to, and the destination its {@code DestinationKeyId} names.
     */
    @Test
    void kmsReEncryptNamesBothKeysItActsOn() {
        byte[] blob = "ciphertext".getBytes(StandardCharsets.UTF_8);
        String encoded = Base64.getEncoder().encodeToString(blob);
        KmsService kms = mock(KmsService.class);
        when(kms.findKeyArn(any(), any())).thenReturn(Optional.empty());
        when(kms.decryptToKeyArn(blob, "us-east-1")).thenReturn(KMS_KEY_ARN);
        when(kms.findKeyArn("alias/destination", "us-east-1")).thenReturn(Optional.of(KMS_OTHER_KEY_ARN));
        ResourceArnBuilder backed = backedBy(kms);

        setJsonBody("{\"CiphertextBlob\":\"" + encoded + "\",\"DestinationKeyId\":\"alias/destination\"}");
        assertEquals(List.of(KMS_KEY_ARN, KMS_OTHER_KEY_ARN),
                backed.buildKmsResources("kms:ReEncrypt", ctx, "us-east-1", ACCOUNT, "aws"));

        when(kms.findKeyArn("alias/source", "us-east-1")).thenReturn(Optional.of(KMS_KEY_ARN));
        setJsonBody("{\"SourceKeyId\":\"alias/source\",\"DestinationKeyId\":\"alias/destination\"}");
        assertEquals(List.of(KMS_KEY_ARN, KMS_OTHER_KEY_ARN),
                backed.buildKmsResources("kms:ReEncrypt", ctx, "us-east-1", ACCOUNT, "aws"));
    }

    /**
     * A {@code ReEncrypt} never reads a {@code KeyId}: its source is the key that encrypted the
     * ciphertext when the request names no {@code SourceKeyId}, so a {@code KeyId} naming
     * another key cannot choose the key the request is authorized against. A caller allowed
     * on the claimed key only could otherwise submit another key's ciphertext and have it
     * re-encrypted under the allowed one.
     */
    @Test
    void kmsReEncryptNamesTheSourceTheHandlerDecryptsWithNotAKeyId() {
        byte[] blob = "ciphertext".getBytes(StandardCharsets.UTF_8);
        String encoded = Base64.getEncoder().encodeToString(blob);
        KmsService kms = mock(KmsService.class);
        when(kms.findKeyArn(any(), any())).thenReturn(Optional.empty());
        when(kms.decryptToKeyArn(blob, "us-east-1")).thenReturn(KMS_KEY_ARN);
        when(kms.findKeyArn("alias/claimed", "us-east-1")).thenReturn(Optional.of(KMS_OTHER_KEY_ARN));
        ResourceArnBuilder backed = backedBy(kms);

        setJsonBody("{\"CiphertextBlob\":\"" + encoded
                + "\",\"KeyId\":\"alias/claimed\",\"DestinationKeyId\":\"" + KMS_OTHER_KEY_ARN + "\"}");
        assertEquals(List.of(KMS_KEY_ARN, KMS_OTHER_KEY_ARN),
                backed.buildKmsResources("kms:ReEncrypt", ctx, "us-east-1", ACCOUNT, "aws"));
    }

    /**
     * A grant operation names the key the grant it acts on belongs to: a token-only
     * {@code RetireGrant} the key the token's grant belongs to, since the handler finds the
     * grant by the token alone, and a {@code RetireGrant} or a {@code RevokeGrant} identifying
     * its grant by {@code KeyId} and {@code GrantId} the key it names together with that grant's
     * key. A token naming no grant the account holds keeps the permissive fallback.
     */
    @Test
    void kmsGrantOperationsNameTheKeyTheGrantTheyActOnBelongsTo() {
        KmsService kms = mock(KmsService.class);
        when(kms.findKeyArn(any(), any())).thenReturn(Optional.empty());
        when(kms.findKeyArn(KMS_KEY_ID, "us-east-1")).thenReturn(Optional.of(KMS_KEY_ARN));
        when(kms.findKeyArn("other-key", "us-east-1")).thenReturn(Optional.of(KMS_OTHER_KEY_ARN));
        when(kms.findGrantKeyArn("grant-token", null, "us-east-1")).thenReturn(Optional.of(KMS_KEY_ARN));
        when(kms.findGrantKeyArn(null, "grant-id", "us-east-1")).thenReturn(Optional.of(KMS_KEY_ARN));
        when(kms.findGrantKeyArn("unknown-token", null, "us-east-1")).thenReturn(Optional.empty());
        ResourceArnBuilder backed = backedBy(kms);

        setJsonBody("{\"GrantToken\":\"grant-token\"}");
        assertEquals(List.of(KMS_KEY_ARN),
                backed.buildKmsResources("kms:RetireGrant", ctx, "us-east-1", ACCOUNT, "aws"));

        setJsonBody("{\"GrantToken\":\"unknown-token\"}");
        assertEquals(List.of("arn:aws:kms:us-east-1:000000000000:key/*"),
                backed.buildKmsResources("kms:RetireGrant", ctx, "us-east-1", ACCOUNT, "aws"));

        setJsonBody("{\"KeyId\":\"" + KMS_KEY_ID + "\",\"GrantId\":\"grant-id\"}");
        assertEquals(List.of(KMS_KEY_ARN),
                backed.buildKmsResources("kms:RetireGrant", ctx, "us-east-1", ACCOUNT, "aws"));
        assertEquals(List.of(KMS_KEY_ARN),
                backed.buildKmsResources("kms:RevokeGrant", ctx, "us-east-1", ACCOUNT, "aws"));

        setJsonBody("{\"KeyId\":\"other-key\",\"GrantId\":\"grant-id\"}");
        assertEquals(List.of(KMS_OTHER_KEY_ARN, KMS_KEY_ARN),
                backed.buildKmsResources("kms:RevokeGrant", ctx, "us-east-1", ACCOUNT, "aws"));

        when(kms.findGrantKeyArn("grant-token", "grant-id", "us-east-1"))
                .thenReturn(Optional.of(KMS_OTHER_KEY_ARN));
        setJsonBody("{\"KeyId\":\"" + KMS_KEY_ID + "\",\"GrantId\":\"grant-id\",\"GrantToken\":\"grant-token\"}");
        assertEquals(List.of(KMS_KEY_ARN),
                backed.buildKmsResources("kms:RevokeGrant", ctx, "us-east-1", ACCOUNT, "aws"));
    }

    // ── SSM ──────────────────────────────────────────────────────────────────────

    @Test
    void ssmBuildsArnFromNameWithLeadingSlash() {
        setJsonBody("{\"Name\":\"/config/env\"}");
        String arn = builder.build("ssm", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:ssm:us-east-1:000000000000:parameter/config/env", arn);
    }

    @Test
    void ssmBuildsArnFromNameWithoutLeadingSlash() {
        setJsonBody("{\"Name\":\"config-key\"}");
        String arn = builder.build("ssm", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:ssm:us-east-1:000000000000:parameter/config-key", arn);
    }

    @Test
    void ssmResourcePolicyActionsActOnTheParameterTheirResourceArnNames() {
        when(ctx.getHeaderString("X-Amz-Target")).thenReturn("AmazonSSM.PutResourcePolicy");
        setJsonBody("{\"ResourceArn\":\"arn:aws:ssm:us-east-1:000000000000:parameter/prod/db\",\"Policy\":\"{}\"}");
        assertEquals("arn:aws:ssm:us-east-1:000000000000:parameter/prod/db",
                builder.build("ssm", ctx, "us-east-1", "000000000000"));

        // Any other action acts on the parameter its handler reads, its Name, whatever else the body carries.
        when(ctx.getHeaderString("X-Amz-Target")).thenReturn("AmazonSSM.GetParameter");
        setJsonBody("{\"Name\":\"/prod/db\",\"ResourceArn\":\"arn:aws:ssm:us-east-1:000000000000:parameter/dev/db\"}");
        assertEquals("arn:aws:ssm:us-east-1:000000000000:parameter/prod/db",
                builder.build("ssm", ctx, "us-east-1", "000000000000"));
        when(ctx.getHeaderString("X-Amz-Target")).thenReturn(null);
        assertEquals("arn:aws:ssm:us-east-1:000000000000:parameter/prod/db",
                builder.build("ssm", ctx, "us-east-1", "000000000000"));
    }

    // ── SQS ─────────────────────────────────────────────────────────────────────

    @Test
    void sqsBuildsArnFromJsonQueueName() {
        setJsonBody("{\"QueueName\":\"order-queue\"}");
        String arn = builder.build("sqs", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:sqs:us-east-1:000000000000:order-queue", arn);
    }

    @Test
    void sqsBuildsArnFromQueryParam() {
        queryParams.putSingle("QueueUrl", "http://localhost:4566/000000000000/my-queue");
        String arn = builder.build("sqs", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:sqs:us-east-1:000000000000:my-queue", arn);
    }

    // ── SNS ─────────────────────────────────────────────────────────────────────

    @Test
    void snsBuildsArnFromFormBodyTopicArn() {
        queryParams.add("TopicArn", "arn:aws:sns:us-east-1:000000000000:allowed");
        setFormBody("Action=Publish&TopicArn=arn%3Aaws%3Asns%3Aus-east-1%3A000000000000%3Aalerts&Message=hi");
        String arn = builder.build("sns", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:sns:us-east-1:000000000000:alerts", arn);
    }

    @Test
    void snsBuildsArnFromJsonTopicArn() {
        String topicArn = "arn:aws:sns:us-east-1:000000000000:alerts";
        setJsonBody("{\"TopicArn\":\"" + topicArn + "\"}");
        String arn = builder.build("sns", ctx, "us-east-1", "000000000000");
        assertEquals(topicArn, arn);
    }

    // ── S3 ──────────────────────────────────────────────────────────────────────

    @Test
    void s3BuildsBucketArn() {
        when(uriInfo.getPath()).thenReturn("/my-bucket");
        String arn = builder.build("s3", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:s3:::my-bucket", arn);
    }

    @Test
    void s3BuildsArnsInTheRequestRegionsPartition() {
        when(uriInfo.getPath()).thenReturn("/my-bucket/key.txt");
        assertEquals("arn:aws-cn:s3:::my-bucket/key.txt",
                builder.build("s3", ctx, "cn-north-1", "000000000000"));
        when(uriInfo.getPath()).thenReturn("/");
        assertEquals("arn:aws-us-gov:s3:::*", builder.build("s3", ctx, "us-gov-west-1", "000000000000"));
    }

    @Test
    void s3BuildsObjectArn() {
        when(uriInfo.getPath()).thenReturn("/my-bucket/folder/file.json");
        String arn = builder.build("s3", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:s3:::my-bucket/folder/file.json", arn);
    }

    /**
     * S3VirtualHostFilter rewrites a virtual-hosted bucket-level request (GET /) to
     * /bucket/. The resource is still the bucket, so the ARN must carry no trailing
     * slash or a policy naming arn:aws:s3:::bucket stops matching and an allowed
     * ListBucket is denied for every SDK that defaults to virtual-hosted addressing.
     */
    @Test
    void s3BuildsBucketArnForVirtualHostedRewrittenPath() {
        when(uriInfo.getPath()).thenReturn("/my-bucket/");
        String arn = builder.build("s3", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:s3:::my-bucket", arn);
    }

    @Test
    void s3KeepsTrailingSlashOfAFolderMarkerKey() {
        when(uriInfo.getPath()).thenReturn("/my-bucket/folder/");
        String arn = builder.build("s3", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:s3:::my-bucket/folder/", arn);
    }

    // ── S3 Control ─────────────────────────────────────────────────────────────

    /**
     * An S3 Control tagging route names the resource it acts on by its ARN, and that ARN as
     * written is what the request is checked against. The data-plane reading of the path, which
     * issue #6537 reports, checked s3:GetObject on a bucket named v20180820, so a policy
     * allowing the tagging action on the real bucket never matched. The ARN is the caller's own
     * naming of the resource, kept as the ARN-carrying requests of other services are: the plain
     * general-purpose form and the directory-bucket form the Go SDK and Terraform send alike.
     */
    @Test
    void s3ControlTaggingIsCheckedAgainstTheArnTheRouteNames() {
        for (String action : List.of("s3:ListTagsForResource", "s3:TagResource", "s3:UntagResource")) {
            when(uriInfo.getPath()).thenReturn("/v20180820/tags/arn:aws:s3:::repro-bucket");
            assertEquals(List.of("arn:aws:s3:::repro-bucket"),
                    builder.buildResources("s3", action, ctx, "us-east-1", ACCOUNT, "aws"), action);
            when(uriInfo.getPath())
                    .thenReturn("/v20180820/tags/arn:aws:s3:us-east-1:000000000000:bucket/repro-bucket");
            assertEquals(List.of("arn:aws:s3:us-east-1:000000000000:bucket/repro-bucket"),
                    builder.buildResources("s3", action, ctx, "us-east-1", ACCOUNT, "aws"), action);
        }
    }

    /** A tagging route naming no ARN stays on the wildcard, under which the handler refuses it. */
    @Test
    void s3ControlTaggingNamingNoArnLeavesTheWildcard() {
        when(uriInfo.getPath()).thenReturn("/v20180820/tags/not-an-arn");
        assertEquals(List.of("*"),
                builder.buildResources("s3", "s3:ListTagsForResource", ctx, "us-east-1", ACCOUNT, "aws"));
    }

    /**
     * AWS gives s3:ListAccessPoints and the account-level Block Public Access actions no
     * resource type, so the request is checked against the wildcard alone, as the no-resource
     * actions of other namespaces are (the KMS list operations): a bucket-scoped statement,
     * {@code arn:<partition>:s3:::*} or any other ARN, grants them nothing, and a NotResource
     * naming any bucket denies the call.
     */
    @Test
    void s3ControlAccountRoutesAreCheckedAgainstTheWildcardAlone() {
        when(uriInfo.getPath()).thenReturn("/v20180820/accesspoint");
        assertEquals(List.of("*"),
                builder.buildResources("s3", "s3:ListAccessPoints", ctx, "us-east-1", ACCOUNT, "aws"));
        when(uriInfo.getPath()).thenReturn("/v20180820/configuration/publicAccessBlock");
        assertEquals(List.of("*"),
                builder.buildResources("s3", "s3:PutAccountPublicAccessBlock", ctx, "us-east-1", ACCOUNT, "aws"));
        assertEquals(List.of("*"),
                builder.buildResources("s3", "s3:GetAccountPublicAccessBlock", ctx, "us-east-1", ACCOUNT, "aws"));
        assertEquals(List.of("*"),
                builder.buildResources("s3", "s3:PutAccountPublicAccessBlock", ctx, "us-gov-west-1", ACCOUNT,
                        "aws-us-gov"));
    }

    /**
     * The operation tells S3 Control's requests from the data plane, not the path: a request a
     * data-plane action claims under the same prefix, a bucket actually named v20180820, keeps
     * the bucket and object ARN of its own path.
     */
    @Test
    void aDataPlaneOperationUnderTheS3ControlPrefixKeepsItsOwnArn() {
        when(uriInfo.getPath()).thenReturn("/v20180820/tags/key.txt");
        assertEquals(List.of("arn:aws:s3:::v20180820/tags/key.txt"),
                builder.buildResources("s3", "s3:PutObject", ctx, "us-east-1", ACCOUNT, "aws"));
        when(uriInfo.getPath()).thenReturn("/v20180820");
        assertEquals(List.of("arn:aws:s3:::v20180820"),
                builder.buildResources("s3", "s3:ListBucket", ctx, "us-east-1", ACCOUNT, "aws"));
    }

    // ── The vendored table of request resources ─────────────────────────────────

    /**
     * A namespace with no branch here is checked on the resources the vendored table finds in its request for the
     * operation it is dispatched as, and on {@code *} without one or for an operation of another namespace.
     */
    @Test
    void aNamespaceWithNoBranchIsCheckedOnTheResourcesItsRequestNames() {
        when(ctx.getMethod()).thenReturn("POST");
        setJsonBody("{\"UserPoolId\":\"us-east-1_abc\"}");
        assertEquals(List.of("arn:aws:cognito-idp:us-east-1:000000000000:userpool/us-east-1_abc"), builder.buildResources(
                "cognito-idp", "cognito-idp:DescribeUserPool", ctx, "us-east-1", "000000000000", "aws"));
        assertEquals(List.of("*"), builder.buildResources("cognito-idp", ctx, "us-east-1", "000000000000"));
        assertEquals(List.of("*"), builder.buildResources("glue", "cognito-idp:DescribeUserPool", ctx, "us-east-1",
                "000000000000", "aws"));
        setJsonBody("{}");
        assertEquals(List.of("*"), builder.buildResources("cognito-idp", "cognito-idp:DescribeUserPool", ctx,
                "us-east-1", "000000000000", "aws"));
    }

    // ── Lambda ──────────────────────────────────────────────────────────────────

    @Test
    void lambdaBuildsFunctionArn() {
        when(uriInfo.getPath()).thenReturn("/2015-03-31/functions/my-function/invocations");
        String arn = builder.build("lambda", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:lambda:us-east-1:000000000000:function:my-function", arn);
    }

    /** CreateFunction is checked on the function its body names, in the caller's account and Region. */
    @Test
    void lambdaCreateFunctionIsCheckedOnTheFunctionItsBodyNames() {
        when(uriInfo.getPath()).thenReturn("/2015-03-31/functions");
        when(ctx.getMethod()).thenReturn("POST");
        setJsonBody("{\"FunctionName\":\"my-function\",\"Runtime\":\"nodejs20.x\"}");
        assertEquals("arn:aws:lambda:us-east-1:000000000000:function:my-function",
                builder.build("lambda", ctx, "us-east-1", "000000000000"));
        setJsonBody("{\"FunctionName\":\"arn:aws:lambda:us-east-1:111122223333:function:my-function\"}");
        assertEquals("arn:aws:lambda:us-east-1:000000000000:function:my-function",
                builder.build("lambda", ctx, "us-east-1", "000000000000"));
        setJsonBody("{\"Runtime\":\"nodejs20.x\"}");
        assertEquals("*", builder.build("lambda", ctx, "us-east-1", "000000000000"));
        when(ctx.getMethod()).thenReturn("GET");
        setJsonBody("{\"FunctionName\":\"my-function\"}");
        assertEquals("*", builder.build("lambda", ctx, "us-east-1", "000000000000"));
    }

    @Test
    void lambdaFunctionArnKeepsTheAccountItNames() {
        when(uriInfo.getPath()).thenReturn(
                "/2015-03-31/functions/arn:aws:lambda:us-east-1:111122223333:function:my-function/invocations");
        assertEquals("arn:aws:lambda:us-east-1:111122223333:function:my-function",
                builder.build("lambda", ctx, "us-east-1", "000000000000"));

        when(uriInfo.getPath()).thenReturn("/2015-03-31/functions/111122223333:function:my-function/invocations");
        assertEquals("arn:aws:lambda:us-east-1:111122223333:function:my-function",
                builder.build("lambda", ctx, "us-east-1", "000000000000"));
    }

    @Test
    void lambdaCodeSigningUsesTheNamespacedFunctionTarget() {
        when(uriInfo.getPath()).thenReturn(
                "/2020-06-30/functions/function:protected/code-signing-config");
        assertEquals("arn:aws:lambda:us-east-1:000000000000:function:protected",
                builder.build("lambda", ctx, "us-east-1", ACCOUNT));
    }

    @Test
    void lambdaInvokeKeepsTheQualifierItRuns() {
        when(uriInfo.getPath()).thenReturn(
                "/2015-03-31/functions/arn:aws:lambda:us-east-1:111122223333:function:my-function:live/invocations");
        assertEquals("arn:aws:lambda:us-east-1:111122223333:function:my-function:live",
                builder.build("lambda", ctx, "us-east-1", "000000000000"));

        when(uriInfo.getPath()).thenReturn("/2015-03-31/functions/my-function/invocations");
        queryParams.putSingle("Qualifier", "1");
        assertEquals("arn:aws:lambda:us-east-1:000000000000:function:my-function:1",
                builder.build("lambda", ctx, "us-east-1", "000000000000"));
    }

    @Test
    void provisionedConcurrencyKeepsTheOwnerAndQualifierItConfigures() {
        when(uriInfo.getPath()).thenReturn(
                "/2019-09-30/functions/arn:aws:lambda:us-east-1:111122223333:function:example/provisioned-concurrency");
        queryParams.putSingle("Qualifier", "live");
        assertEquals("arn:aws:lambda:us-east-1:111122223333:function:example:live",
                builder.build("lambda", ctx, "us-east-1", "000000000000"));
        when(uriInfo.getPath()).thenReturn("/2019-09-30/functions/example/provisioned-concurrency");
        assertEquals("arn:aws:lambda:us-east-1:000000000000:function:example:live",
                builder.build("lambda", ctx, "us-east-1", "000000000000"));
    }

    @Test
    void lambdaWebRoutesResolveTheFunctionRevisionOrEndpointTheyActOn() {
        String fn = "arn:aws:lambda:us-east-1:000000000000:web-function/app";
        when(uriInfo.getPath()).thenReturn("/2025-03-07/web-functions/app");
        assertEquals(fn, builder.build("lambda", ctx, "us-east-1", "000000000000"));
        when(uriInfo.getPath()).thenReturn("/2025-03-07/web-functions/app/revisions");
        assertEquals(fn, builder.build("lambda", ctx, "us-east-1", "000000000000"));
        when(uriInfo.getPath()).thenReturn("/2025-03-07/web-functions/app/list-endpoints");
        assertEquals(fn, builder.build("lambda", ctx, "us-east-1", "000000000000"));
        when(uriInfo.getPath()).thenReturn("/2025-03-07/web-functions/app/revisions/rev-abcde");
        assertEquals(fn + "/revision/rev-abcde", builder.build("lambda", ctx, "us-east-1", "000000000000"));
        when(uriInfo.getPath()).thenReturn("/2025-03-07/web-functions/app/endpoints/prod");
        assertEquals(fn + "/endpoint/prod", builder.build("lambda", ctx, "us-east-1", "000000000000"));
        // A function named by its ARN keeps the account and region it names.
        when(uriInfo.getPath()).thenReturn(
                "/2025-03-07/web-functions/arn:aws:lambda:us-east-1:111122223333:web-function/app/endpoints/prod");
        assertEquals("arn:aws:lambda:us-east-1:111122223333:web-function/app/endpoint/prod",
                builder.build("lambda", ctx, "us-east-1", "000000000000"));
        // A revision or endpoint named by its ARN resolves to its id under the path's function.
        when(uriInfo.getPath()).thenReturn("/2025-03-07/web-functions/app/revisions/"
                + "arn:aws:lambda:us-east-1:000000000000:web-function/app/revision/rev-abcde");
        assertEquals(fn + "/revision/rev-abcde", builder.build("lambda", ctx, "us-east-1", "000000000000"));
        when(uriInfo.getPath()).thenReturn("/2025-03-07/web-functions/app/endpoints/"
                + "arn:aws:lambda:us-east-1:000000000000:web-function/app/endpoint/prod");
        assertEquals(fn + "/endpoint/prod", builder.build("lambda", ctx, "us-east-1", "000000000000"));
        // The tag and policy routes carry the function ARN in the path.
        when(uriInfo.getPath()).thenReturn("/2025-03-07/tags/" + fn);
        assertEquals(fn, builder.build("lambda", ctx, "us-east-1", "000000000000"));
        when(uriInfo.getPath()).thenReturn("/2025-03-07/resource-policy/" + fn);
        assertEquals(fn, builder.build("lambda", ctx, "us-east-1", "000000000000"));
        // Listing functions, the account settings and creating a function name no resource.
        when(uriInfo.getPath()).thenReturn("/2025-03-07/web-functions");
        assertEquals("*", builder.build("lambda", ctx, "us-east-1", "000000000000"));
        when(uriInfo.getPath()).thenReturn("/2025-03-07/web-account-settings");
        assertEquals("*", builder.build("lambda", ctx, "us-east-1", "000000000000"));
    }

    @Test
    void lambdaMicrovmAndNetworkConnectorRoutesResolveTheImageOrConnectorTheyName() {
        String image = "arn:aws:lambda:us-east-1:000000000000:microvm-image:img";
        when(uriInfo.getPath()).thenReturn("/2025-09-09/microvm-images/img");
        assertEquals(image, builder.build("lambda", ctx, "us-east-1", "000000000000"));
        when(uriInfo.getPath()).thenReturn("/2025-09-09/microvm-images/img/versions/1/builds/b-1");
        assertEquals(image, builder.build("lambda", ctx, "us-east-1", "000000000000"));
        // An image named by its ARN is looked up by its name in the request's account and Region, so that
        // is the resource checked, whatever account or Region the ARN names.
        when(uriInfo.getPath()).thenReturn(
                "/2025-09-09/microvm-images/arn:aws:lambda:eu-west-1:111122223333:microvm-image:shared/versions");
        assertEquals("arn:aws:lambda:us-east-1:000000000000:microvm-image:shared",
                builder.build("lambda", ctx, "us-east-1", "000000000000"));
        when(uriInfo.getPath()).thenReturn("/2026-04-04/network-connectors/nc-1/versions");
        assertEquals("arn:aws:lambda:us-east-1:000000000000:network-connector:nc-1",
                builder.build("lambda", ctx, "us-east-1", "000000000000"));
        // RunMicrovm runs the image its imageIdentifier names, read as the service reads it.
        when(ctx.getMethod()).thenReturn("POST");
        when(uriInfo.getPath()).thenReturn("/2025-09-09/microvms");
        setJsonBody("{\"imageIdentifier\":\"img\"}");
        assertEquals(image, builder.build("lambda", ctx, "us-east-1", "000000000000"));
        setJsonBody("{\"imageIdentifier\":\"arn:aws:lambda:eu-west-1:111122223333:microvm-image:img\"}");
        assertEquals(image, builder.build("lambda", ctx, "us-east-1", "000000000000"));
        setJsonBody("{}");
        assertEquals("*", builder.build("lambda", ctx, "us-east-1", "000000000000"));
        // A managed image's versions are checked against the managed image, which aws owns.
        when(ctx.getMethod()).thenReturn("GET");
        when(uriInfo.getPath()).thenReturn("/2025-09-09/managed-microvm-images/al2023-1/versions");
        assertEquals("arn:aws:lambda:us-east-1:aws:microvm-image:al2023-1",
                builder.build("lambda", ctx, "us-east-1", "000000000000"));
        // Creating or listing either, listing MicroVMs, a managed image there is none of, and a MicroVM the
        // account does not hold name no resource here.
        for (String path : new String[] {"/2025-09-09/microvm-images", "/2025-09-09/managed-microvm-images",
                "/2025-09-09/managed-microvm-images/other/versions", "/2025-09-09/microvms",
                "/2025-09-09/microvms/vm-1", "/2026-04-04/network-connectors"}) {
            when(uriInfo.getPath()).thenReturn(path);
            assertEquals("*", builder.build("lambda", ctx, "us-east-1", "000000000000"), path);
        }
    }

    @Test
    void lambdaMappingConfigAndLayerRoutesResolveTheResourceTheyName() {
        when(ctx.getMethod()).thenReturn("GET");
        request("/2015-03-31/event-source-mappings/14e0db71-5d35-4eb5-b481-8945cf9d10c2");
        assertEquals("arn:aws:lambda:us-east-1:000000000000:event-source-mapping:14e0db71-5d35-4eb5-b481-8945cf9d10c2",
                builder.build("lambda", ctx, "us-east-1", ACCOUNT));
        // A config is looked up by the id that ends its ARN, in the request's account and Region.
        String config = "arn:aws:lambda:us-east-1:000000000000:code-signing-config:csc-0123456789abcdef0";
        for (String path : new String[] {"/2020-04-22/code-signing-configs/" + config,
                "/2020-04-22/code-signing-configs/" + config + "/functions",
                "/2020-04-22/code-signing-configs/arn:aws:lambda:eu-west-1:111122223333:code-signing-config:"
                        + "csc-0123456789abcdef0"}) {
            request(path);
            assertEquals(config, builder.build("lambda", ctx, "us-east-1", ACCOUNT), path);
        }
        // A layer version, its policy and its permissions.
        String version = "arn:aws:lambda:us-east-1:000000000000:layer:my-layer:3";
        for (String path : new String[] {"/2018-10-31/layers/my-layer/versions/3",
                "/2018-10-31/layers/my-layer/versions/3/policy",
                "/2018-10-31/layers/my-layer/versions/3/policy/statement-1"}) {
            request(path);
            assertEquals(version, builder.build("lambda", ctx, "us-east-1", ACCOUNT), path);
        }
        // Lambda looks a layer named by an ARN up by that name as given, not as the layer the ARN names, so
        // the layer of that name is the one checked.
        String named = "arn:aws:lambda:us-east-1:000000000000:layer:my-layer";
        request("/2018-10-31/layers/" + named + "/versions/3");
        assertEquals("arn:aws:lambda:us-east-1:000000000000:layer:" + named + ":3",
                builder.build("lambda", ctx, "us-east-1", ACCOUNT));
        // GetLayerVersionByArn reads the version its Arn names.
        request("/2018-10-31/layers");
        queryParams.putSingle("find", "LayerVersion");
        queryParams.putSingle("Arn", version);
        assertEquals(version, builder.build("lambda", ctx, "us-east-1", ACCOUNT));
        queryParams.clear();
        // Listing layers, a layer's versions or configs, and creating a config, name no resource.
        for (String path : new String[] {"/2018-10-31/layers", "/2018-10-31/layers/my-layer/versions",
                "/2020-04-22/code-signing-configs"}) {
            request(path);
            assertEquals("*", builder.build("lambda", ctx, "us-east-1", ACCOUNT), path);
        }
        when(ctx.getMethod()).thenReturn("POST");
        request("/2020-04-22/code-signing-configs");
        assertEquals("*", builder.build("lambda", ctx, "us-east-1", ACCOUNT));
        // PublishLayerVersion makes a version of the layer, so it is checked against the layer.
        request("/2018-10-31/layers/my-layer/versions");
        assertEquals("arn:aws:lambda:us-east-1:000000000000:layer:my-layer",
                builder.build("lambda", ctx, "us-east-1", ACCOUNT));
        // A function's own settings, such as its concurrency or versions, are checked against the function.
        request("/2015-03-31/functions/my-function/versions");
        assertEquals("arn:aws:lambda:us-east-1:000000000000:function:my-function",
                builder.build("lambda", ctx, "us-east-1", ACCOUNT));
        when(ctx.getMethod()).thenReturn("PUT");
        request("/2017-10-31/functions/my-function/concurrency");
        assertEquals("arn:aws:lambda:us-east-1:000000000000:function:my-function",
                builder.build("lambda", ctx, "us-east-1", ACCOUNT));
    }

    @Test
    void aLayerVersionIsReadAsLambdaReadsItsNameAndNumber() {
        // Lambda parses the version number, so 01 is version 1, and the router keeps an encoded / inside the
        // layer's name, so the layer checked is the one Lambda looks up.
        when(ctx.getMethod()).thenReturn("GET");
        request("/2018-10-31/layers/my-layer/versions/01");
        assertEquals("arn:aws:lambda:us-east-1:000000000000:layer:my-layer:1",
                builder.build("lambda", ctx, "us-east-1", ACCOUNT));
        request("/2018-10-31/layers/my%2Flayer/versions/3/policy");
        assertEquals("arn:aws:lambda:us-east-1:000000000000:layer:my/layer:3",
                builder.build("lambda", ctx, "us-east-1", ACCOUNT));
        when(ctx.getMethod()).thenReturn("POST");
        request("/2018-10-31/layers/my%2Flayer/versions");
        assertEquals("arn:aws:lambda:us-east-1:000000000000:layer:my/layer",
                builder.build("lambda", ctx, "us-east-1", ACCOUNT));
        // GetLayerVersionByArn parses the number the same way.
        when(ctx.getMethod()).thenReturn("GET");
        request("/2018-10-31/layers");
        queryParams.putSingle("find", "LayerVersion");
        queryParams.putSingle("Arn", "arn:aws:lambda:us-east-1:000000000000:layer:my-layer:01");
        assertEquals("arn:aws:lambda:us-east-1:000000000000:layer:my-layer:1",
                builder.build("lambda", ctx, "us-east-1", ACCOUNT));
    }

    @Test
    void anActionOnARunningMicrovmIsCheckedAgainstTheImageItRuns() {
        String image = "arn:aws:lambda:us-east-1:000000000000:microvm-image:img";
        LambdaMicrovmsService.Microvm vm = new LambdaMicrovmsService.Microvm();
        vm.microvmId = "microvm-1";
        vm.imageArn = image;
        LambdaMicrovmsService microvms = mock(LambdaMicrovmsService.class);
        when(microvms.findMicrovm(any(), any())).thenReturn(Optional.empty());
        when(microvms.findMicrovm("us-east-1", "microvm-1")).thenReturn(Optional.of(vm));
        ResourceArnBuilder backed = backedBy(microvms);

        for (String path : new String[] {"/2025-09-09/microvms/microvm-1",
                "/2025-09-09/microvms/microvm-1/suspend", "/2025-09-09/microvms/microvm-1/auth-token"}) {
            when(uriInfo.getPath()).thenReturn(path);
            assertEquals(image, backed.build("lambda", ctx, "us-east-1", "000000000000"), path);
        }
        when(uriInfo.getPath()).thenReturn("/2025-09-09/microvms/microvm-2");
        assertEquals("*", backed.build("lambda", ctx, "us-east-1", "000000000000"));
    }

    @Test
    void dsqlRoutesResolveTheClusterTheTaggedArnOrNone() {
        String cluster = "arn:aws:dsql:eu-west-1:000000000000:cluster/abcdefghijklmnopqrstuvwxyz";
        for (String path : List.of("/cluster/abcdefghijklmnopqrstuvwxyz", "/cluster/abcdefghijklmnopqrstuvwxyz/policy",
                "/clusters/abcdefghijklmnopqrstuvwxyz/vpc-endpoint-service-name", "/tags/" + cluster)) {
            when(uriInfo.getPath()).thenReturn(path);
            assertEquals(List.of(cluster), builder.buildResources("dsql", ctx, "eu-west-1", "000000000000"), path);
        }
        // The same tagging is served at /tags, the ARN in the resourceArn query parameter or body field.
        when(uriInfo.getPath()).thenReturn("/tags");
        queryParams.putSingle("resourceArn", cluster);
        assertEquals(List.of(cluster), builder.buildResources("dsql", ctx, "eu-west-1", "000000000000"));
        queryParams.clear();
        when(ctx.getMethod()).thenReturn("POST");
        setJsonBody("{\"resourceArn\":\"" + cluster + "\",\"tags\":{}}");
        assertEquals(List.of(cluster), builder.buildResources("dsql", ctx, "eu-west-1", "000000000000"));
        when(ctx.getMethod()).thenReturn("GET");
        // A stream route names the stream, or its cluster to create or list streams.
        when(uriInfo.getPath()).thenReturn("/stream/abcdefghijklmnopqrstuvwxyz");
        assertEquals(List.of(cluster), builder.buildResources("dsql", ctx, "eu-west-1", "000000000000"));
        when(uriInfo.getPath()).thenReturn("/stream/abcdefghijklmnopqrstuvwxyz/s1");
        assertEquals(List.of(cluster + "/stream/s1"), builder.buildResources("dsql", ctx, "eu-west-1", "000000000000"));
        // CreateCluster and ListClusters are checked against every cluster, as DSQL checks them (measured).
        when(uriInfo.getPath()).thenReturn("/cluster");
        assertEquals(List.of("arn:aws:dsql:eu-west-1:000000000000:cluster/*"),
                builder.buildResources("dsql", ctx, "eu-west-1", "000000000000"));
        when(uriInfo.getPath()).thenReturn("/cluster/abcdefghijklmnopqrstuvwxyz");
        assertEquals(List.of("arn:aws-cn:dsql:polygondwanaland-west-1:000000000000:cluster/abcdefghijklmnopqrstuvwxyz"),
                builder.buildResources("dsql", ctx, "polygondwanaland-west-1", "000000000000", "aws-cn"));
    }

    @Test
    void apiGatewayRoutesResolveThePathTheyNameWithNoAccount() {
        request("/restapis/abc123/stages");
        assertEquals(List.of("arn:aws:apigateway:us-east-1::/restapis/abc123/stages"),
                builder.buildResources("apigateway", ctx, "us-east-1", ACCOUNT));
        // Each segment is decoded as the router decodes it, so an encoded id names the API it routes to.
        request("/restapis/%61bc123/stages");
        assertEquals(List.of("arn:aws:apigateway:us-east-1::/restapis/abc123/stages"),
                builder.buildResources("apigateway", ctx, "us-east-1", ACCOUNT));
        // Dot segments are resolved after decoding, as the router resolves them.
        request("/restapis/x/%2E%2E/abc123/stages");
        assertEquals(List.of("arn:aws:apigateway:us-east-1::/restapis/abc123/stages"),
                builder.buildResources("apigateway", ctx, "us-east-1", ACCOUNT));
        request("/restapis/%2e/abc123");
        assertEquals(List.of("arn:aws:apigateway:us-east-1::/restapis/abc123"),
                builder.buildResources("apigateway", ctx, "us-east-1", ACCOUNT));
        // Empty segments are dropped once the dots are resolved, as the router collapses them.
        request("/restapis//abc123");
        assertEquals(List.of("arn:aws:apigateway:us-east-1::/restapis/abc123"),
                builder.buildResources("apigateway", ctx, "us-east-1", ACCOUNT));
        request("/restapis/x/..//abc123");
        assertEquals(List.of("arn:aws:apigateway:us-east-1::/restapis/abc123"),
                builder.buildResources("apigateway", ctx, "us-east-1", ACCOUNT));
        request("/restapis/x//../abc123");
        assertEquals(List.of("arn:aws:apigateway:us-east-1::/restapis/x/abc123"),
                builder.buildResources("apigateway", ctx, "us-east-1", ACCOUNT));
        request("/v2/tags/arn:aws:apigateway:us-east-1::/apis/x/%2E%2E/a1b2c3");
        assertEquals(List.of("arn:aws:apigateway:us-east-1::/tags/arn%3Aaws%3Aapigateway%3Aus-east-1%3A%3A%2Fapis%2Fa1b2c3"),
                builder.buildResources("apigateway", ctx, "us-east-1", ACCOUNT));
        request("/restapis/");
        assertEquals(List.of("arn:aws:apigateway:us-east-1::/restapis"),
                builder.buildResources("apigateway", ctx, "us-east-1", ACCOUNT));
        // An HTTP or WebSocket API path's /v2 is not part of the resource it names.
        request("/v2/apis");
        assertEquals(List.of("arn:aws:apigateway:us-east-1::/apis"),
                builder.buildResources("apigateway", ctx, "us-east-1", ACCOUNT));
        request("/v2/apis/a1b2c3/stages/prod");
        assertEquals(List.of("arn:aws:apigateway:us-east-1::/apis/a1b2c3/stages/prod"),
                builder.buildResources("apigateway", ctx, "us-east-1", ACCOUNT));
        // A stage name keeps the characters it is written with, sent literally or encoded.
        request("/v2/apis/a1b2c3/stages/$default");
        assertEquals(List.of("arn:aws:apigateway:us-east-1::/apis/a1b2c3/stages/$default"),
                builder.buildResources("apigateway", ctx, "us-east-1", ACCOUNT));
        request("/v2/apis/a1b2c3/stages/%24default");
        assertEquals(List.of("arn:aws:apigateway:us-east-1::/apis/a1b2c3/stages/$default"),
                builder.buildResources("apigateway", ctx, "us-east-1", ACCOUNT));
        request("/v2domains");
        assertEquals(List.of("arn:aws:apigateway:us-east-1::/v2domains"),
                builder.buildResources("apigateway", ctx, "us-east-1", ACCOUNT));
        // A tagging route names the ARN it tags still encoded, as AWS names its Tags resource.
        request("/v2/tags/arn%3Aaws%3Aapigateway%3Aus-east-1%3A%3A%2Fapis%2Fa1b2c3");
        assertEquals(List.of("arn:aws:apigateway:us-east-1::/tags/arn%3Aaws%3Aapigateway%3Aus-east-1%3A%3A%2Fapis%2Fa1b2c3"),
                builder.buildResources("apigateway", ctx, "us-east-1", ACCOUNT));
        request("/v2/tags/arn:aws:apigateway:us-east-1::/apis/a1b2c3");
        assertEquals(List.of("arn:aws:apigateway:us-east-1::/tags/arn%3Aaws%3Aapigateway%3Aus-east-1%3A%3A%2Fapis%2Fa1b2c3"),
                builder.buildResources("apigateway", ctx, "us-east-1", ACCOUNT));
        request("/tags/arn%3aaws%3aapigateway%3aus-east-1%3a%3a%2frestapis%2fabc123");
        assertEquals(List.of("arn:aws:apigateway:us-east-1::/tags/arn%3Aaws%3Aapigateway%3Aus-east-1%3A%3A%2Frestapis%2Fabc123"),
                builder.buildResources("apigateway", ctx, "us-east-1", ACCOUNT));
        request("/restapis");
        assertEquals(List.of("arn:aws-us-gov:apigateway:us-gov-west-1::/restapis"),
                builder.buildResources("apigateway", ctx, "us-gov-west-1", ACCOUNT, "aws-us-gov"));
    }

    @Test
    void schedulerRoutesResolveTheScheduleInItsGroupTheGroupOrTheTaggedArn() {
        when(uriInfo.getPath()).thenReturn("/schedules/nightly");
        assertEquals("arn:aws:scheduler:us-east-1:000000000000:schedule/default/nightly",
                builder.build("scheduler", ctx, "us-east-1", "000000000000"));
        queryParams.putSingle("groupName", "reports");
        assertEquals("arn:aws:scheduler:us-east-1:000000000000:schedule/reports/nightly",
                builder.build("scheduler", ctx, "us-east-1", "000000000000"));
        // A create or update names its group in the body, which Scheduler reads, not in the query, which
        // it ignores for them: a groupName there does not move the check to another group.
        when(ctx.getMethod()).thenReturn("POST");
        setJsonBody("{\"GroupName\":\"billing\",\"ScheduleExpression\":\"rate(1 hour)\"}");
        assertEquals("arn:aws:scheduler:us-east-1:000000000000:schedule/billing/nightly",
                builder.build("scheduler", ctx, "us-east-1", "000000000000"));
        queryParams.putSingle("groupName", "reports");
        setJsonBody("{\"GroupName\":\"billing\",\"ScheduleExpression\":\"rate(1 hour)\"}");
        assertEquals("arn:aws:scheduler:us-east-1:000000000000:schedule/billing/nightly",
                builder.build("scheduler", ctx, "us-east-1", "000000000000"));
        queryParams.clear();
        when(ctx.getMethod()).thenReturn("PUT");
        setJsonBody("{\"ScheduleExpression\":\"rate(1 hour)\"}");
        assertEquals("arn:aws:scheduler:us-east-1:000000000000:schedule/default/nightly",
                builder.build("scheduler", ctx, "us-east-1", "000000000000"));
        when(ctx.getMethod()).thenReturn("GET");
        queryParams.clear();
        when(uriInfo.getPath()).thenReturn("/schedule-groups/reports");
        assertEquals("arn:aws:scheduler:us-east-1:000000000000:schedule-group/reports",
                builder.build("scheduler", ctx, "us-east-1", "000000000000"));
        String group = "arn:aws:scheduler:us-east-1:000000000000:schedule-group/reports";
        when(uriInfo.getPath()).thenReturn("/tags/" + group);
        assertEquals(group, builder.build("scheduler", ctx, "us-east-1", "000000000000"));
        // The same tagging is served at /tags, the ARN in the resourceArn query parameter or body field.
        when(uriInfo.getPath()).thenReturn("/tags");
        queryParams.putSingle("resourceArn", group);
        assertEquals(group, builder.build("scheduler", ctx, "us-east-1", "000000000000"));
        queryParams.clear();
        when(ctx.getMethod()).thenReturn("POST");
        setJsonBody("{\"resourceArn\":\"" + group + "\",\"tags\":[]}");
        assertEquals(group, builder.build("scheduler", ctx, "us-east-1", "000000000000"));
        // POST /tags tags the body's ARN, so a resourceArn in the query does not move the check.
        queryParams.putSingle("resourceArn", "arn:aws:scheduler:us-east-1:000000000000:schedule-group/other");
        setJsonBody("{\"resourceArn\":\"" + group + "\",\"tags\":[]}");
        assertEquals(group, builder.build("scheduler", ctx, "us-east-1", "000000000000"));
        queryParams.clear();
        when(uriInfo.getPath()).thenReturn("/schedules");
        assertEquals("*", builder.build("scheduler", ctx, "us-east-1", "000000000000"));
    }

    @Test
    void schedulerAndPipesArnsAreMintedInTheRequestsPartition() {
        // A Region no partition publishes takes the deployment's partition, as Scheduler and Pipes mint
        // their ARNs, rather than aws.
        when(uriInfo.getPath()).thenReturn("/schedules/nightly");
        assertEquals(List.of("arn:aws-cn:scheduler:xx-local-1:000000000000:schedule/default/nightly"),
                builder.buildResources("scheduler", ctx, "xx-local-1", "000000000000", "aws-cn"));
        when(uriInfo.getPath()).thenReturn("/schedule-groups/reports");
        assertEquals(List.of("arn:aws-cn:scheduler:xx-local-1:000000000000:schedule-group/reports"),
                builder.buildResources("scheduler", ctx, "xx-local-1", "000000000000", "aws-cn"));
        when(uriInfo.getPath()).thenReturn("/v1/pipes/orders");
        assertEquals(List.of("arn:aws-cn:pipes:xx-local-1:000000000000:pipe/orders"),
                builder.buildResources("pipes", ctx, "xx-local-1", "000000000000", "aws-cn"));
    }

    @Test
    void theTaggedArnIsReadWhereTheSharedTaggingControllersReadIt() {
        String arn = "arn:aws:pipes:us-east-1:000000000000:pipe/orders";
        when(ctx.getMethod()).thenReturn("GET");
        when(uriInfo.getPath()).thenReturn("/tags/" + arn);
        assertEquals(arn, builder.taggedArn(ctx));
        when(uriInfo.getPath()).thenReturn("/v1/tags/" + arn);
        assertEquals(arn, builder.taggedArn(ctx));
        when(uriInfo.getPath()).thenReturn("/tags");
        queryParams.putSingle("resourceArn", arn);
        assertEquals(arn, builder.taggedArn(ctx));
        when(ctx.getMethod()).thenReturn("DELETE");
        assertEquals(arn, builder.taggedArn(ctx));
        // POST /v1/tags lists the tags of the ARN its query names, and POST /tags tags the one its body names.
        when(ctx.getMethod()).thenReturn("POST");
        when(uriInfo.getPath()).thenReturn("/v1/tags");
        assertEquals(arn, builder.taggedArn(ctx));
        when(uriInfo.getPath()).thenReturn("/tags");
        setJsonBody("{\"tags\":[]}");
        assertNull(builder.taggedArn(ctx));
        when(uriInfo.getPath()).thenReturn("/v1/pipes/orders");
        assertNull(builder.taggedArn(ctx));
    }

    @Test
    void lambdaTagRoutesAndAStreamingInvokeResolveTheResourceTheyName() {
        // Tagging acts on the caller's own function, image or connector of the ARN's Region and name,
        // whatever account the ARN names, so that is the resource checked.
        when(uriInfo.getPath()).thenReturn("/2017-03-31/tags/arn:aws:lambda:eu-west-1:111122223333:function:shared");
        assertEquals("arn:aws:lambda:eu-west-1:000000000000:function:shared",
                builder.build("lambda", ctx, "us-east-1", "000000000000"));
        when(uriInfo.getPath()).thenReturn(
                "/2017-03-31/tags/arn:aws:lambda:eu-west-1:111122223333:microvm-image:img");
        assertEquals("arn:aws:lambda:eu-west-1:000000000000:microvm-image:img",
                builder.build("lambda", ctx, "us-east-1", "000000000000"));
        when(uriInfo.getPath()).thenReturn(
                "/2017-03-31/tags/arn:aws:lambda:eu-west-1:111122223333:network-connector:nc-1");
        assertEquals("arn:aws:lambda:eu-west-1:000000000000:network-connector:nc-1",
                builder.build("lambda", ctx, "us-east-1", "000000000000"));
        // A mapping's ARN has to name it exactly, and Lambda refuses a qualified function, a running MicroVM
        // and an image in no Region, so these stay as written.
        String mapping = "arn:aws:lambda:us-east-1:111122223333:event-source-mapping:"
                + "14e0db71-5d35-4eb5-b481-8945cf9d10c2";
        for (String arn : new String[] {mapping, "arn:aws:lambda:us-east-1:111122223333:function:shared:prod",
                "arn:aws:lambda:us-east-1:111122223333:microvm:vm-1", "arn:aws:lambda::111122223333:microvm-image:img"}) {
            when(uriInfo.getPath()).thenReturn("/2017-03-31/tags/" + arn);
            assertEquals(arn, builder.build("lambda", ctx, "us-east-1", "000000000000"), arn);
        }
        // The MicroVM tag handler takes a target without the arn: prefix too, and reads it the same way.
        when(uriInfo.getPath()).thenReturn("/2017-03-31/tags/x:y:z:eu-west-1:microvm-image:img");
        assertEquals("arn:aws:lambda:eu-west-1:000000000000:microvm-image:img",
                builder.build("lambda", ctx, "us-east-1", "000000000000"));
        when(uriInfo.getPath()).thenReturn("/2017-03-31/tags/shared");
        assertEquals("*", builder.build("lambda", ctx, "us-east-1", "000000000000"));
        // A streaming invoke is an invoke: its qualifier and the account its function ARN names count.
        when(uriInfo.getPath()).thenReturn("/2021-11-15/functions/my-function/response-streaming-invocations");
        queryParams.putSingle("Qualifier", "prod");
        assertEquals("arn:aws:lambda:us-east-1:000000000000:function:my-function:prod",
                builder.build("lambda", ctx, "us-east-1", "000000000000"));
    }

    @Test
    void pipesRoutesResolveThePipeOrTheTaggedArn() {
        String pipe = "arn:aws:pipes:us-east-1:000000000000:pipe/orders";
        when(uriInfo.getPath()).thenReturn("/v1/pipes/orders");
        assertEquals(pipe, builder.build("pipes", ctx, "us-east-1", "000000000000"));
        when(uriInfo.getPath()).thenReturn("/v1/pipes/orders/start");
        assertEquals(pipe, builder.build("pipes", ctx, "us-east-1", "000000000000"));
        when(uriInfo.getPath()).thenReturn("/tags/" + pipe);
        assertEquals(pipe, builder.build("pipes", ctx, "us-east-1", "000000000000"));
        when(uriInfo.getPath()).thenReturn("/v1/pipes");
        assertEquals("*", builder.build("pipes", ctx, "us-east-1", "000000000000"));
    }

    @Test
    void lambdaRoutesOtherThanInvokeAreCheckedAgainstTheCallersUnqualifiedFunction() {
        when(uriInfo.getPath()).thenReturn(
                "/2015-03-31/functions/arn:aws:lambda:us-east-1:111122223333:function:my-function:1/code");
        assertEquals("arn:aws:lambda:us-east-1:000000000000:function:my-function",
                builder.build("lambda", ctx, "us-east-1", "000000000000"));

        when(uriInfo.getPath()).thenReturn("/2015-03-31/functions/111122223333:function:my-function/policy");
        assertEquals("arn:aws:lambda:us-east-1:000000000000:function:my-function",
                builder.build("lambda", ctx, "us-east-1", "000000000000"));

        when(uriInfo.getPath()).thenReturn("/2015-03-31/functions/my-function");
        queryParams.putSingle("Qualifier", "1");
        assertEquals("arn:aws:lambda:us-east-1:000000000000:function:my-function",
                builder.build("lambda", ctx, "us-east-1", "000000000000"));
    }

    @Test
    void lambdaQualifiedReadsByArnAreCheckedInTheAccountTheyRead() {
        when(ctx.getMethod()).thenReturn("GET");
        when(uriInfo.getPath()).thenReturn(
                "/2015-03-31/functions/arn:aws:lambda:us-east-1:111122223333:function:my-function:1");
        assertEquals("arn:aws:lambda:us-east-1:111122223333:function:my-function",
                builder.build("lambda", ctx, "us-east-1", "000000000000"));

        when(uriInfo.getPath()).thenReturn(
                "/2015-03-31/functions/arn:aws:lambda:us-east-1:111122223333:function:my-function/configuration");
        queryParams.putSingle("Qualifier", "live");
        assertEquals("arn:aws:lambda:us-east-1:111122223333:function:my-function",
                builder.build("lambda", ctx, "us-east-1", "000000000000"));
    }

    @Test
    void lambdaReadsThatDoNotFollowTheArnAreCheckedOnTheCallersFunction() {
        when(ctx.getMethod()).thenReturn("GET");
        when(uriInfo.getPath()).thenReturn(
                "/2015-03-31/functions/arn:aws:lambda:us-east-1:111122223333:function:my-function");
        assertEquals("arn:aws:lambda:us-east-1:000000000000:function:my-function",
                builder.build("lambda", ctx, "us-east-1", "000000000000"));

        when(uriInfo.getPath()).thenReturn("/2015-03-31/functions/111122223333:function:my-function:1");
        assertEquals("arn:aws:lambda:us-east-1:000000000000:function:my-function",
                builder.build("lambda", ctx, "us-east-1", "000000000000"));

        when(uriInfo.getPath()).thenReturn(
                "/2015-03-31/functions/arn:aws:lambda:us-east-1:111122223333:function:my-function:1/policy");
        assertEquals("arn:aws:lambda:us-east-1:000000000000:function:my-function",
                builder.build("lambda", ctx, "us-east-1", "000000000000"));

        when(ctx.getMethod()).thenReturn("DELETE");
        when(uriInfo.getPath()).thenReturn(
                "/2015-03-31/functions/arn:aws:lambda:us-east-1:111122223333:function:my-function:1");
        assertEquals("arn:aws:lambda:us-east-1:000000000000:function:my-function",
                builder.build("lambda", ctx, "us-east-1", "000000000000"));
    }

    @Test
    void lambdaReferenceLambdaRefusesIsNamedAsGiven() {
        when(uriInfo.getPath()).thenReturn("/2015-03-31/functions/not:a:function:ref/invocations");
        assertEquals("arn:aws:lambda:us-east-1:000000000000:function:not:a:function:ref",
                builder.build("lambda", ctx, "us-east-1", "000000000000"));
    }

    @Test
    void lambdaDurableExecutionRoutesNameTheExecution() {
        when(uriInfo.getPath()).thenReturn("/2025-12-01/durable-executions/arn:aws:lambda:us-east-1:000000000000:"
                + "function:my-function:$LATEST/durable-execution/run-1/0b1c/stop");
        String arn = builder.build("lambda", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:lambda:us-east-1:000000000000:function:my-function:$LATEST/durable-execution/run-1/0b1c",
                arn);
    }

    @Test
    void lambdaDurableCallbackRoutesNameTheExecutionInTheCallbackId() {
        String executionArn = "arn:aws:lambda:us-east-1:000000000000:function:my-function:1/durable-execution/run-1/0b1c";
        String callbackId = Base64.getEncoder().encodeToString((executionArn + "|c1|nonce").getBytes(StandardCharsets.UTF_8));
        when(uriInfo.getPath()).thenReturn("/2025-12-01/durable-execution-callbacks/" + callbackId + "/succeed");
        assertEquals(executionArn, builder.build("lambda", ctx, "us-east-1", "000000000000"));

        when(uriInfo.getPath()).thenReturn("/2025-12-01/durable-execution-callbacks/not-a-callback/heartbeat");
        assertEquals("*", builder.build("lambda", ctx, "us-east-1", "000000000000"));
    }

    @Test
    void lambdaDurableExecutionGetNamesTheExecution() {
        when(uriInfo.getPath()).thenReturn("/2025-12-01/durable-executions/arn:aws:lambda:us-east-1:000000000000:"
                + "function:my-function:1/durable-execution/run-1/0b1c");
        String arn = builder.build("lambda", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:lambda:us-east-1:000000000000:function:my-function:1/durable-execution/run-1/0b1c", arn);
    }

    // ── EKS ──────────────────────────────────────────────────────────────────────

    /** The cluster {@code held}, which EKS keeps in us-west-2 and finds by its name alone, and what it holds. */
    private static final String HELD_CLUSTER = "arn:aws:eks:us-west-2:000000000000:cluster/held";
    private static final String WORKER = "arn:aws:iam::000000000000:role/team/worker";
    private static final String RUNNER = "arn:aws:iam::000000000000:role/ci/access-policies/runner";

    private static ResourceArnBuilder eksHolding() {
        EksService eks = mock(EksService.class);
        when(eks.findResourceArn(any(), any(), any())).thenReturn(Optional.empty());
        when(eks.findResourceArn("held", null, null)).thenReturn(Optional.of(HELD_CLUSTER));
        for (String[] held : new String[][] {
            {"node-groups", "ng", "nodegroup/held/ng/1"}, {"node-groups", "a/b", "nodegroup/held/a/b/2"},
            {"fargate-profiles", "fp", "fargateprofile/held/fp/3"}, {"addons", "vpc-cni", "addon/held/vpc-cni/4"},
            {"updates", "u-1", "addon/held/vpc-cni/4"},
            {"pod-identity-associations", "a-1", "podidentityassociation/held/a-1"},
            {"access-entries", WORKER, "access-entry/held/role/000000000000/worker/5"},
            {"access-entries", WORKER + "/access-policies", "access-entry/held/role/000000000000/access-policies/6"},
            {"access-entries", RUNNER, "access-entry/held/role/000000000000/runner/7"},
        }) {
            when(eks.findResourceArn("held", held[0], held[1]))
                    .thenReturn(Optional.of("arn:aws:eks:us-west-2:000000000000:" + held[2]));
        }
        return backedBy(eks);
    }

    private String eksResource(ResourceArnBuilder eks, String method, String sent) {
        when(ctx.getMethod()).thenReturn(method);
        request(sent);
        return eks.build("eks", ctx, "us-east-1", ACCOUNT);
    }

    @Test
    void anEksClusterRouteActsOnTheClusterEksHoldsOrTheOneACreateMints() {
        ResourceArnBuilder eks = eksHolding();
        String minted = "arn:aws:eks:us-east-1:000000000000:cluster/";
        assertEquals(HELD_CLUSTER, eksResource(eks, "GET", "/clusters/held"));
        assertEquals(minted + "gone", eksResource(eks, "DELETE", "/clusters/gone"));
        for (String route : List.of("/node-groups", "/addons", "/access-entries", "/pod-identity-associations",
                "/update-config", "/updates", "/updates/u-1/cancel-update", "/insights/i-1",
                "/certificate-authorities/ca-1/activate", "/identity-provider-configs/associate")) {
            assertEquals(HELD_CLUSTER, eksResource(eks, "POST", "/clusters/held" + route), route);
        }
        assertEquals(HELD_CLUSTER, eksResource(eks, "DELETE", "/cluster-registrations/held"));

        // A create is checked against the cluster its body names; listing clusters names none.
        setJsonBody("{\"name\":\"fresh\",\"roleArn\":\"arn:aws:iam::000000000000:role/r\"}");
        assertEquals(minted + "fresh", eksResource(eks, "POST", "/clusters"));
        assertEquals("*", eksResource(eks, "GET", "/clusters"));
    }

    @Test
    void anEksRouteOnWhatAClusterHoldsActsOnTheArnEksHoldsItUnder() {
        ResourceArnBuilder eks = eksHolding();
        String held = "arn:aws:eks:us-west-2:000000000000:";
        assertEquals(held + "nodegroup/held/ng/1", eksResource(eks, "GET", "/clusters/held/node-groups/ng"));
        assertEquals(held + "nodegroup/held/ng/1",
                eksResource(eks, "POST", "/clusters/held/node-groups/ng/update-version"));
        // The router keeps an encoded / inside the name, and EKS looks the node group up by it.
        assertEquals(held + "nodegroup/held/a/b/2", eksResource(eks, "GET", "/clusters/held/node-groups/a%2Fb"));
        assertEquals(held + "fargateprofile/held/fp/3", eksResource(eks, "DELETE", "/clusters/held/fargate-profiles/fp"));
        assertEquals(held + "addon/held/vpc-cni/4", eksResource(eks, "POST", "/clusters/held/addons/vpc-cni/update"));
        assertEquals(held + "podidentityassociation/held/a-1",
                eksResource(eks, "POST", "/clusters/held/pod-identity-associations/a-1"));
        // An access entry's principal is the rest of the path, encoded or not.
        assertEquals(held + "access-entry/held/role/000000000000/worker/5", eksResource(eks, "GET",
                "/clusters/held/access-entries/arn%3Aaws%3Aiam%3A%3A000000000000%3Arole%2Fteam%2Fworker"));
        assertEquals(held + "access-entry/held/role/000000000000/worker/5",
                eksResource(eks, "DELETE", "/clusters/held/access-entries/" + WORKER));
        // An entry's access policy routes act on the entry, as the router serves them.
        String admin = "/access-policies/arn:aws:eks::aws:cluster-access-policy/AmazonEKSClusterAdminPolicy";
        assertEquals(held + "access-entry/held/role/000000000000/worker/5",
                eksResource(eks, "GET", "/clusters/held/access-entries/" + WORKER + "/access-policies"));
        assertEquals(held + "access-entry/held/role/000000000000/worker/5",
                eksResource(eks, "POST", "/clusters/held/access-entries/" + WORKER + "/access-policies"));
        assertEquals(held + "access-entry/held/role/000000000000/worker/5",
                eksResource(eks, "DELETE", "/clusters/held/access-entries/" + WORKER + admin));
        assertEquals(held + "access-entry/held/role/000000000000/worker/5", eksResource(eks, "DELETE",
                "/clusters/held/access-entries/" + WORKER
                        + "/access-policies/arn%3Aaws%3Aeks%3A%3Aaws%3Acluster-access-policy%2FAdmin"));
        // Any other route under the entry takes the rest of the path as its principal, an IAM path holding
        // access-policies included.
        assertEquals(held + "access-entry/held/role/000000000000/runner/7",
                eksResource(eks, "DELETE", "/clusters/held/access-entries/" + RUNNER));
        assertEquals(held + "access-entry/held/role/000000000000/access-policies/6",
                eksResource(eks, "DELETE", "/clusters/held/access-entries/" + WORKER + "/access-policies"));
        // What EKS does not hold names no resource, nor do what Floci keeps no id for.
        assertEquals("*", eksResource(eks, "GET", "/clusters/held/node-groups/other"));
        assertEquals("*", eksResource(eks, "GET", "/clusters/gone/addons/vpc-cni"));
        assertEquals("*", eksResource(eks, "POST", "/clusters/held/identity-provider-configs/describe"));
        assertEquals("*", eksResource(eks, "GET", "/clusters/held/capabilities/argocd"));
    }

    @Test
    void anEksUpdateActsOnItsAddonAndATagOnTheCallersClusterOfThatName() {
        ResourceArnBuilder eks = eksHolding();
        // EKS returns an add-on's update whatever add-on the request names, if any, so it acts on its own add-on.
        String addon = "arn:aws:eks:us-west-2:000000000000:addon/held/vpc-cni/4";
        assertEquals(addon, eksResource(eks, "GET", "/clusters/held/updates/u-1"));
        queryParams.putSingle("addonName", "coredns");
        assertEquals(addon, eksResource(eks, "GET", "/clusters/held/updates/u-1"));
        queryParams.clear();
        assertEquals(HELD_CLUSTER, eksResource(eks, "GET", "/clusters/held/updates/u-2"));
        assertEquals(HELD_CLUSTER, eksResource(eks, "POST", "/clusters/held/updates/u-1/cancel-update"));

        // EKS tags the caller's cluster named after the ARN's last /, whatever account or Region it names.
        assertEquals(HELD_CLUSTER, eksResource(eks, "POST", "/tags/arn:aws:eks:eu-west-1:111122223333:cluster/held"));
        queryParams.putSingle("resourceArn", "arn:aws:eks:eu-west-1:111122223333:cluster/held");
        assertEquals(HELD_CLUSTER, eksResource(eks, "DELETE", "/tags"));
        queryParams.clear();
        assertEquals("*", eksResource(eks, "GET", "/tags/no-slash"));

        assertEquals("arn:aws:eks:us-east-1:000000000000:eks-anywhere-subscription/sub-1",
                eksResource(eks, "GET", "/eks-anywhere-subscriptions/sub-1"));
        for (String route : List.of("/addons/supported-versions", "/access-policies", "/cluster-versions",
                "/cluster-registrations", "/eks-anywhere-subscriptions")) {
            assertEquals("*", eksResource(eks, "GET", route), route);
        }
    }

    /** Stubs the request path as sent and, decoded, as {@link UriInfo#getPath()} gives it. */
    private void request(String sent) {
        when(uriInfo.getRequestUri()).thenReturn(URI.create("http://localhost:4566" + sent));
        when(uriInfo.getBaseUri()).thenReturn(URI.create("http://localhost:4566/"));
        when(uriInfo.getPath()).thenReturn(URI.create(sent).getPath());
    }
}
