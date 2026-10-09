package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.iam.model.IamGroup;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.services.iam.model.IamUser;
import io.github.hectorvent.floci.services.iam.model.InstanceProfile;
import io.github.hectorvent.floci.services.iam.model.ServerCertificate;
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
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
     * A policy, MFA device, SAML provider or OIDC provider is named by an ARN the request already
     * carries, so it is passed through. Rebuilding it from parts would only invent a second
     * spelling of the resource the caller already named.
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
        when(store.findUserNameByAccessKeyId("AKIAEXAMPLE")).thenReturn(Optional.of("bob"));
        assertEquals(arn, resourceOf(backedBy(store),
                "Action=GetAccessKeyLastUsed&AccessKeyId=AKIAEXAMPLE"));
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
        String arn = builder.build("secretsmanager", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:secretsmanager:us-east-1:000000000000:secret:app/prod/database", arn);
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

    // ── Lambda ──────────────────────────────────────────────────────────────────

    @Test
    void lambdaBuildsFunctionArn() {
        when(uriInfo.getPath()).thenReturn("/2015-03-31/functions/my-function/invocations");
        String arn = builder.build("lambda", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:lambda:us-east-1:000000000000:function:my-function", arn);
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
}
