package io.floci.aws.services.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.AwsProtocolClaimFilter;
import io.floci.aws.core.common.AwsRegions;
import io.floci.aws.core.common.ProtocolClaim;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.core.common.RequestPathLabels;
import io.floci.aws.core.common.RequestResources;
import io.floci.aws.core.common.StoredResourceArns;
import io.floci.aws.core.common.WireProtocol;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.cloudwatch.logs.CloudWatchLogsDeliveryService;
import io.floci.aws.services.cloudwatch.logs.CloudWatchLogsStoredResourceArns;
import io.floci.aws.services.cloudwatch.logs.model.Delivery;
import io.floci.aws.services.configservice.AwsConfigService;
import io.floci.aws.services.configservice.ConfigFixtures;
import io.floci.aws.services.configservice.ConfigStoredResourceArns;
import io.floci.aws.services.dynamodb.DynamoDbStoredResourceArns;
import io.floci.aws.services.dynamodb.backend.DynamoDbTableAccess;
import io.floci.aws.services.eks.EksService;
import io.floci.aws.services.eks.EksStoredResourceArns;
import io.floci.aws.services.iam.model.AccessKey;
import io.floci.aws.services.iam.model.IamGroup;
import io.floci.aws.services.iam.model.IamRole;
import io.floci.aws.services.iam.model.IamUser;
import io.floci.aws.services.iam.model.InstanceProfile;
import io.floci.aws.services.iam.model.ServerCertificate;
import io.floci.aws.services.kms.KmsService;
import io.floci.aws.services.kms.KmsStoredResourceArns;
import io.floci.aws.services.lambda.LambdaStoredResourceArns;
import io.floci.aws.services.lambdamicrovms.LambdaMicrovmsService;
import io.floci.aws.services.s3.S3RequestPathLabels;
import io.floci.aws.services.sqs.SqsService;
import io.floci.aws.services.sqs.SqsServiceFactory;
import io.floci.aws.services.sqs.SqsStoredResourceArns;
import io.floci.aws.testing.ParallelLocks;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ResourceLock(ParallelLocks.PORTS)
class ResourceArnBuilderTest {

    private static final String ACCOUNT = "000000000000";

    private ResourceArnBuilder builder;
    private ContainerRequestContext ctx;
    private UriInfo uriInfo;
    private MultivaluedMap<String, String> queryParams;
    private Map<String, Object> contextProperties;
    /** The form {@link #setFormBody} last sent, whose {@code Action} or {@code Operation} is what IAM dispatches. */
    private String sentForm;

    @ParameterizedTest
    @CsvSource({"aws,us-east-1", "aws-us-gov,us-gov-west-1", "aws-cn,cn-north-1"})
    void delegationSetResourcesAreGlobalAndUseTheDecodedId(String partition, String region) {
        for (String id : List.of("N1", "%2Fdelegationset%2FN1")) {
            when(uriInfo.getRequestUri()).thenReturn(URI.create("http://localhost/2013-04-01/delegationset/" + id));
            when(uriInfo.getBaseUri()).thenReturn(URI.create("http://localhost/"));
            for (String operation : List.of("GetReusableDelegationSet", "DeleteReusableDelegationSet")) {
                // The id is read from the route the request matched, the type's path Route 53 returns it under dropped.
                when(ctx.getMethod()).thenReturn(operation.startsWith("Get") ? "GET" : "DELETE");
                assertEquals(List.of("arn:" + partition + ":route53:::delegationset/N1"),
                        builder.buildResources("route53", "route53:" + operation, ctx, region, ACCOUNT, partition));
            }
        }
    }

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
        sentForm = form;
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

    @ParameterizedTest
    @CsvSource({"aws,us-east-1", "aws-us-gov,us-gov-west-1", "aws-cn,cn-north-1"})
    void rdsGroupLifecycleUsesTheNamedGroupAndUnfilteredListsRemainWildcard(String partition, String region) {
        String prefix = "arn:" + partition + ":rds:" + region + ":" + ACCOUNT + ":";
        for (String[] group : List.of(new String[]{"DBParameterGroup", "pg"},
                new String[]{"DBClusterParameterGroup", "cluster-pg"}, new String[]{"DBSubnetGroup", "subgrp"})) {
            for (String verb : List.of("Create", "Modify", "Delete", "Describe")) {
                String action = verb + group[0] + ("Describe".equals(verb) ? "s" : "");
                setFormBody(group[0] + "Name=owned");
                assertEquals(List.of(prefix + group[1] + ":owned"),
                        builder.buildResources("rds", "rds:" + action, ctx, region, ACCOUNT, partition), action);
            }
            setFormBody("");
            assertEquals(List.of("*"), builder.buildResources("rds", "rds:Describe" + group[0] + "s",
                    ctx, region, ACCOUNT, partition));
        }
        for (String action : List.of("AddTagsToResource", "RemoveTagsFromResource", "ListTagsForResource")) {
            setFormBody("ResourceName=" + prefix + "pg:owned");
            assertEquals(List.of(prefix + "pg:owned"),
                    builder.buildResources("rds", "rds:" + action, ctx, region, ACCOUNT, partition));
        }
    }

    @Test
    void rdsCreatesAuthorizeEachExplicitDatabaseAndGroupTarget() {
        String prefix = "arn:aws:rds:us-east-1:" + ACCOUNT + ":";
        setFormBody("DBClusterIdentifier=db&DBClusterParameterGroupName=parameters&DBSubnetGroupName=subnets");
        assertEquals(List.of(prefix + "cluster:db", prefix + "cluster-pg:parameters", prefix + "subgrp:subnets"),
                builder.buildResources("rds", "rds:CreateDBCluster", ctx, "us-east-1", ACCOUNT, "aws"));
        setFormBody("DBInstanceIdentifier=instance&DBClusterIdentifier=db&DBParameterGroupName=parameters"
                + "&DBSubnetGroupName=subnets");
        assertEquals(List.of(prefix + "cluster:db", prefix + "db:instance", prefix + "pg:parameters", prefix + "subgrp:subnets"),
                builder.buildResources("rds", "rds:CreateDBInstance", ctx, "us-east-1", ACCOUNT, "aws"));
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
        return backedBy(service);
    }

    /**
     * A builder reading {@code service} through IAM's stored ARNs, as the filter's does, for the actions that name a
     * resource that exists.
     */
    private static ResourceArnBuilder backedBy(IamService service) {
        @SuppressWarnings("unchecked")
        Instance<IamService> instance = mock(Instance.class);
        when(instance.get()).thenReturn(service);
        @SuppressWarnings("unchecked")
        Instance<DelegationRequestOwnership> delegations = mock(Instance.class);
        when(delegations.isUnsatisfied()).thenReturn(true);
        return reading(new IamStoredResourceArns(delegations, instance, new RegionResolver("us-east-1", ACCOUNT)));
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
        return iamResource(resourceArnBuilder, "us-east-1", ACCOUNT);
    }

    /** The first of {@link #iamResources}, as the filter's single resource. */
    private String iamResource(ResourceArnBuilder resourceArnBuilder, String region, String account) {
        return iamResources(resourceArnBuilder, region, account).getFirst();
    }

    /**
     * The resources the builder gives the IAM request set up, in the request's partition, for the operation IAM
     * dispatches it as: the form's {@code Action}, or its legacy {@code Operation}, or else the query's.
     */
    private List<String> iamResources(ResourceArnBuilder resourceArnBuilder, String region, String account) {
        String action = queryParams.getFirst("Action");
        for (String parameter : sentForm == null ? new String[0] : sentForm.split("&")) {
            if (parameter.startsWith("Action=") || parameter.startsWith("Operation=")) {
                action = parameter.substring(parameter.indexOf('=') + 1);
                break;
            }
        }
        return resourceArnBuilder.buildResources("iam", "iam:" + action, ctx, region, account,
                AwsRegions.partitionFor(region));
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
        // The forward is one of them: the Service Reference does not list it, and its API reference says "that owner
        // gets a default permission to update the delegation request" its DelegationRequestId names.
        for (String action : List.of("AssociateDelegationRequest", "AcceptDelegationRequest",
                "RejectDelegationRequest", "GetDelegationRequest", "SendDelegationToken", "UpdateDelegationRequest")) {
            assertEquals("arn:aws:iam::000000000000:delegation-request/" + id,
                    resourceOf(builder, "Action=" + action + "&DelegationRequestId=" + id), action);
        }
    }

    @Test
    void iamLeavesTheIdOfADelegationRequestItIsAboutToMintOpen() {
        // IAM assigns the id, so the create is checked on a request of any id.
        assertEquals("arn:aws:iam::000000000000:delegation-request/*", resourceOf(builder,
                "Action=CreateDelegationRequest&Description=d&RequestorWorkflowId=w"));
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
                iamResource(builder, "cn-north-1", ACCOUNT));
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
     * {@code SimulatePrincipalPolicy} is checked on each user, role or group its request names by ARN, as the table
     * reads every member holding an ARN of a type the action acts on: its {@code PolicySourceArn}, and a
     * {@code CallerArn} or {@code ResourceArns} entry naming one too. A resource of another service there names nothing
     * of IAM's, so a simulation of S3 objects is checked on its source principal alone.
     */
    @Test
    void iamChecksASimulationOnEachPrincipalItsRequestNames() {
        String alice = "arn:aws:iam::000000000000:user/alice";
        String bob = "arn:aws:iam::000000000000:user/bob";
        setFormBody("Action=SimulatePrincipalPolicy&ActionNames.member.1=s3%3AGetObject"
                + "&PolicySourceArn=" + URLEncoder.encode(alice, StandardCharsets.UTF_8)
                + "&ResourceArns.member.1=" + URLEncoder.encode(bob, StandardCharsets.UTF_8)
                + "&ResourceArns.member.2=arn%3Aaws%3As3%3A%3A%3Abucket%2Fkey");
        assertEquals(List.of(alice, bob), iamResources(builder, "us-east-1", ACCOUNT));
        setFormBody("Action=SimulatePrincipalPolicy&ActionNames.member.1=s3%3AGetObject"
                + "&PolicySourceArn=" + URLEncoder.encode(alice, StandardCharsets.UTF_8)
                + "&ResourceArns.member.1=arn%3Aaws%3As3%3A%3A%3Abucket%2Fkey");
        assertEquals(List.of(alice), iamResources(builder, "us-east-1", ACCOUNT));
    }

    /**
     * A SAML provider's ARN is its bare name, which carries no path. An OIDC provider's is its URL without the scheme,
     * which no source the table reads says of the request's {@code Url}, so that create names nothing and is checked
     * on {@code *}.
     */
    @Test
    void iamNamesASamlProviderByItsNameAndAnOidcCreateByNothing() {
        assertEquals("arn:aws:iam::000000000000:saml-provider/corp",
                resourceOf(builder, "Action=CreateSAMLProvider&Name=corp"));
        assertEquals("*", resourceOf(builder, "Action=CreateOpenIDConnectProvider"
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
                iamResources(backedBy(storeHolding("bob", "/", userArn)), "us-east-1", ACCOUNT));

        String groupArn = "arn:aws:iam::000000000000:group/managers";
        IamGroup group = new IamGroup();
        group.setGroupName("managers");
        group.setPath("/");
        group.setArn(groupArn);
        IamService store = mock(IamService.class);
        when(store.findGroup(ACCOUNT, "managers")).thenReturn(Optional.of(group));
        setFormBody("Action=UpdateGroup&GroupName=managers&NewGroupName=mgrs");
        assertEquals(List.of(groupArn, "arn:aws:iam::000000000000:group/mgrs"),
                iamResources(backedBy(store), "us-east-1", ACCOUNT));
    }

    /**
     * {@code CreateServiceLinkedRole} does not name the role it creates: AWS derives it from {@code AWSServiceName},
     * which no source the table reads says, so the create is checked on a role with its path and name open.
     */
    @Test
    void iamChecksAServiceLinkedCreateOnAnyRole() {
        assertEquals("arn:aws:iam::000000000000:role/*", resourceOf(builder, "Action=CreateServiceLinkedRole"
                + "&AWSServiceName=autoscaling.amazonaws.com&CustomSuffix=fleet"));
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
        assertEquals(List.of("*"), iamResources(backedBy(iam), "us-east-1", stranger));
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
     * An action whose resource the request does not name stays at {@code *}: the ones the reference gives no resource
     * type, and the ones acting on the user who signs them, with no stored ARNs to give that user.
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
     * The role a deletion task deleted, which IAM gives for the task it holds: the role itself is already gone by the
     * time the status is asked for. A task IAM does not hold names nothing rather than half a resource.
     */
    @Test
    void iamNamesTheRoleADeletionTaskIdentifies() {
        String task = "task/aws-service-role/lex.amazonaws.com/AWSServiceRoleForLexBots/"
                + "12345678-1234-1234-1234-123456789012";
        String role = "arn:aws:iam::000000000000:role/aws-service-role/lex.amazonaws.com/AWSServiceRoleForLexBots";
        IamService store = mock(IamService.class);
        when(store.findServiceLinkedRoleDeletionArn(task)).thenReturn(Optional.of(role));
        when(store.findServiceLinkedRoleDeletionArn("nonsense")).thenReturn(Optional.empty());
        assertEquals(role, resourceOf(backedBy(store), "Action=GetServiceLinkedRoleDeletionStatus&DeletionTaskId="
                + URLEncoder.encode(task, StandardCharsets.UTF_8)));
        assertEquals("*", resourceOf(backedBy(store),
                "Action=GetServiceLinkedRoleDeletionStatus&DeletionTaskId=nonsense"));
    }

    /**
     * An operation acting on the user who signs it when its request names none, as {@code GetUser} with no
     * {@code UserName} and {@code ChangePassword} always do, is checked on that user's stored ARN, path included, or on
     * the account root's for a key of the root; a role session's key names no user, and its {@code UserName}, given,
     * names the user it gives.
     */
    @Test
    void iamNamesTheUserWhoSignsARequestNamingNone() {
        String bob = "arn:aws:iam::000000000000:user/team/bob";
        IamService store = storeHolding("bob", "/team/", bob);
        when(store.findUserNameByAccessKeyId("AKIABOB")).thenReturn(Optional.of("bob"));
        when(store.resolveCallerArn("AKIAROOT")).thenReturn(Optional.of("arn:aws:iam::000000000000:root"));
        when(store.resolveCallerArn("ASIASESSION"))
                .thenReturn(Optional.of("arn:aws:sts::000000000000:assumed-role/app/s"));
        ResourceArnBuilder stored = backedBy(store);
        when(ctx.getHeaderString("Authorization")).thenReturn(signedBy("AKIABOB"));
        assertEquals(bob, resourceOf(stored, "Action=GetUser"));
        assertEquals(bob, resourceOf(stored, "Action=ChangePassword&OldPassword=a&NewPassword=b"));
        assertEquals(bob, resourceOf(stored, "Action=ListAccessKeys"));
        assertEquals("arn:aws:iam::000000000000:user/alice", resourceOf(stored, "Action=GetUser&UserName=alice"));
        when(ctx.getHeaderString("Authorization")).thenReturn(signedBy("AKIAROOT"));
        assertEquals("arn:aws:iam::000000000000:root", resourceOf(stored, "Action=GetUser"));
        when(ctx.getHeaderString("Authorization")).thenReturn(signedBy("ASIASESSION"));
        assertEquals("*", resourceOf(stored, "Action=GetUser"));
    }

    /** A SigV4 {@code Authorization} header signed with {@code accessKeyId}. */
    private static String signedBy(String accessKeyId) {
        return "AWS4-HMAC-SHA256 Credential=" + accessKeyId + "/20261123/us-east-1/iam/aws4_request, "
                + "SignedHeaders=host, Signature=0";
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
        assertEquals(arn, iamResource(backedBy("my-cert", arn), "us-east-1", "000000000000"));
    }

    @Test
    void iamNamesTheCertificateForEveryOperationThatTakesOne() {
        String arn = "arn:aws:iam::000000000000:server-certificate/c1";
        ResourceArnBuilder stored = backedBy("c1", arn);
        for (String action : List.of("GetServerCertificate", "UpdateServerCertificate",
                "DeleteServerCertificate", "TagServerCertificate", "UntagServerCertificate",
                "ListServerCertificateTags")) {
            setFormBody("Action=" + action + "&ServerCertificateName=c1");
            assertEquals(arn, iamResource(stored, "us-east-1", "000000000000"), action);
        }
        // The upload is the one that mints rather than reads: nothing is stored yet.
        setFormBody("Action=UploadServerCertificate&ServerCertificateName=c1");
        assertEquals(arn, iamResource(builder, "us-east-1", "000000000000"));
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
                                iamResource(stored, region, "000000000000"),
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
                iamResources(backedBy("old", stored), "us-east-1", "000000000000"));
    }

    /** A move renames the path, so the destination path is named the same way. */
    @Test
    void iamNamesTheDestinationPathOnAMove() {
        String stored = "arn:aws:iam::000000000000:server-certificate/old";
        setFormBody("Action=UpdateServerCertificate&ServerCertificateName=old&NewPath=/team/");
        assertEquals(
                List.of(stored, "arn:aws:iam::000000000000:server-certificate/team/old"),
                iamResources(backedBy("old", stored), "us-east-1", "000000000000"));
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
                iamResources(backedBy("old", stored), "us-east-1", "000000000000"));
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
                iamResources(backedBy("old", stored), "us-east-1", "000000000000"));
    }

    @Test
    void missingGetRoleUsesItsNameButCannotAcceptAnInjectedPath() {
        setFormBody("Action=GetRole&RoleName=local-storage&Path=/injected/");
        assertEquals("arn:aws:iam::000000000000:role/local-storage",
                iamResource(builder, "us-east-1", ACCOUNT));
        assertEquals("arn:aws-us-gov:iam::000000000000:role/local-storage",
                iamResource(builder, "us-gov-west-1", ACCOUNT));
    }

    @Test
    void ec2TagsAuthorizeEachInstanceAndKeepUnmodelledResourcesVisible() {
        setFormBody("Action=CreateTags&ResourceId.1=i-first&ResourceId.2=i-second&Tag.1.Key=Owner&Tag.1.Value=a");
        assertEquals(List.of("arn:aws:ec2:us-east-1:000000000000:instance/i-first",
                        "arn:aws:ec2:us-east-1:000000000000:instance/i-second"),
                builder.buildResources("ec2", "ec2:CreateTags", ctx, "us-east-1", ACCOUNT, "aws"));
        setFormBody("Action=DeleteTags&ResourceId.1=i-first&ResourceId.2=vol-second");
        assertEquals(List.of("arn:aws-us-gov:ec2:us-gov-west-1:000000000000:instance/i-first", "*"),
                builder.buildResources("ec2", "ec2:DeleteTags", ctx, "us-gov-west-1", ACCOUNT, "aws-us-gov"));
        assertEquals(List.of("*"), builder.buildResources("ec2", "ec2:DescribeInstances", ctx,
                "us-east-1", ACCOUNT, "aws"));
    }

    @Test
    void instanceTagTargetsUseHandlerOrderFirstDuplicatesAndStopAtTheFirstGap() {
        queryParams.add("ResourceId.1", "i-query-decoy");
        setFormBody("Action=CreateTags&ResourceId.1=i-first&ResourceId.1=i-duplicate"
                + "&ResourceId.2=i-second&ResourceId.4=i-gap");
        assertEquals(List.of("arn:aws-cn:ec2:cn-north-1:111122223333:instance/i-first",
                        "arn:aws-cn:ec2:cn-north-1:111122223333:instance/i-second"),
                builder.buildResources("ec2", "ec2:CreateTags", ctx, "cn-north-1", "111122223333", "aws-cn"));
        setFormBody("Action=CreateTags&ResourceId.2=i-not-dispatched");
        assertEquals(List.of("*"),
                builder.buildResources("ec2", "ec2:CreateTags", ctx, "us-east-1", ACCOUNT, "aws"));
        setFormBody("Action=CreateTags&ResourceId.1=i-first&ResourceId.2=i-first");
        assertEquals(List.of("arn:aws:ec2:us-east-1:000000000000:instance/i-first",
                        "arn:aws:ec2:us-east-1:000000000000:instance/i-first"),
                builder.buildResources("ec2", "ec2:CreateTags", ctx, "us-east-1", ACCOUNT, "aws"));
    }

    @ParameterizedTest
    @CsvSource({"StartInstances,aws-cn,cn-north-1", "StopInstances,aws-us-gov,us-gov-west-1",
            "RebootInstances,aws-cn,cn-north-1", "TerminateInstances,aws-us-gov,us-gov-west-1"})
    void instanceLifecycleTargetsUseOnlyTheHandlersInstanceIds(String operation, String partition, String region) {
        queryParams.add("InstanceId.1", "i-query-decoy");
        setFormBody("Action=" + operation + "&InstanceId.1=i-first&InstanceId.1=i-duplicate"
                + "&InstanceId.2=i-second&InstanceId.3=i-first&InstanceId.5=i-gap&ResourceId.1=i-decoy");
        String prefix = "arn:" + partition + ":ec2:" + region + ":111122223333:instance/";
        assertEquals(List.of(prefix + "i-first", prefix + "i-second", prefix + "i-first"),
                builder.buildResources("ec2", "ec2:" + operation, ctx, region, "111122223333", partition));
        setFormBody("Action=" + operation + "&InstanceId.2=i-gap&ResourceId.1=i-decoy");
        assertEquals(List.of("*"),
                builder.buildResources("ec2", "ec2:" + operation, ctx, region, "111122223333", partition));
        when(ctx.getMethod()).thenReturn("GET");
        contextProperties.put(AwsProtocolClaimFilter.CLAIM_PROPERTY,
                new ProtocolClaim(WireProtocol.AWS_QUERY, null, operation, null));
        queryParams.clear();
        queryParams.add("Action", operation);
        queryParams.add("InstanceId.1", "i-first");
        queryParams.add("InstanceId.1", "i-decoy");
        queryParams.add("InstanceId.2", "i-second");
        queryParams.add("InstanceId.4", "i-gap");
        assertEquals(List.of(prefix + "i-first", prefix + "i-second"),
                builder.buildResources("ec2", "ec2:" + operation, ctx, region, "111122223333", partition));
    }

    @Test
    void largeTagBatchReusesTheDecodedFormAndLeavesTheBodyForTheHandler() throws IOException {
        StringBuilder form = new StringBuilder("Action=CreateTags");
        List<String> expected = new ArrayList<>();
        for (int i = 1; i <= 1000; i++) {
            form.append("&ResourceId.").append(i).append("=i-").append(i);
            expected.add("arn:aws:ec2:us-east-1:000000000000:instance/i-" + i);
        }
        form.append("&ResourceId.1000=i-decoy&ResourceId.1002=i-gap&Tag.1.Key=Owner&Tag.1.Value=team%2Ba");
        setFormBody(form.toString());
        queryParams.add("ResourceId.1", "i-query-decoy");
        assertEquals(expected, builder.buildResources("ec2", "ec2:CreateTags", ctx, "us-east-1", ACCOUNT, "aws"));
        clearInvocations(ctx);

        assertEquals(expected, builder.buildResources("ec2", "ec2:CreateTags", ctx, "us-east-1", ACCOUNT, "aws"));
        assertEquals(expected, builder.buildResources("ec2", "ec2:DeleteTags", ctx, "us-east-1", ACCOUNT, "aws"));
        verify(ctx, never()).getEntityStream();
        assertEquals(form.toString(), new String(ctx.getEntityStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test
    void missingRoleOperationsUseTheModelRatherThanAnInjectedPath() {
        queryParams.add("RoleName", "query-decoy");
        setFormBody("Operation=GetRole&RoleName=first&RoleName=duplicate&Path=/injected/");
        assertEquals("arn:aws:iam::111122223333:role/first",
                iamResource(builder, "us-east-1", "111122223333"));
        for (String action : List.of("DeleteRole", "ListRolePolicies", "TagRole", "UpdateRole")) {
            setFormBody("Action=" + action + "&RoleName=first&Path=/injected/");
            assertEquals("arn:aws:iam::" + ACCOUNT + ":role/first",
                    iamResource(builder, "us-east-1", ACCOUNT), action);
        }
        setFormBody("Action=GetRole");
        assertEquals("*", iamResource(builder, "us-east-1", ACCOUNT));
    }

    @Test
    void queryGetUsesTheSameMissingRoleAndInstanceResources() {
        when(ctx.getMethod()).thenReturn("GET");
        contextProperties.put(AwsProtocolClaimFilter.CLAIM_PROPERTY,
                new ProtocolClaim(WireProtocol.AWS_QUERY, null, null, null));
        queryParams.add("Action", "GetRole");
        queryParams.add("RoleName", "first");
        queryParams.add("RoleName", "duplicate");
        queryParams.add("Path", "/injected/");
        assertEquals("arn:aws:iam::000000000000:role/first", iamResource(builder, "us-east-1", ACCOUNT));
        queryParams.clear();
        queryParams.add("Action", "CreateTags");
        queryParams.add("ResourceId.1", "i-first");
        queryParams.add("ResourceId.1", "i-duplicate");
        queryParams.add("ResourceId.2", "i-second");
        assertEquals(List.of("arn:aws:ec2:us-east-1:000000000000:instance/i-first",
                        "arn:aws:ec2:us-east-1:000000000000:instance/i-second"),
                builder.buildResources("ec2", "ec2:CreateTags", ctx, "us-east-1", ACCOUNT, "aws"));
    }

    /** A certificate IAM does not hold is named as its name alone names it, under the root path. */
    @Test
    void iamNamesACertificateItDoesNotHoldUnderTheRootPath() {
        setFormBody("Action=DeleteServerCertificate&ServerCertificateName=absent");
        assertEquals("arn:aws:iam::000000000000:server-certificate/absent",
                iamResource(backedBy("other", "arn:aws:iam::000000000000:server-certificate/other"), "us-east-1",
                        "000000000000"));
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
                iamResource(builder, "us-east-1", "000000000000"));

        // Written without surrounding slashes, it still lands as a path segment.
        setFormBody("Action=UploadServerCertificate&ServerCertificateName=c1&Path=team");
        assertEquals("arn:aws:iam::000000000000:server-certificate/team/c1",
                iamResource(builder, "us-east-1", "000000000000"));

        // Omitted, it is the root path.
        setFormBody("Action=UploadServerCertificate&ServerCertificateName=c1");
        assertEquals("arn:aws:iam::000000000000:server-certificate/c1",
                iamResource(builder, "us-east-1", "000000000000"));
    }

    /**
     * IAM is global, so the ARN carries no region. An upload creates the certificate in the
     * caller's own partition, so that is the partition its ARN is minted in.
     */
    @Test
    void iamMintsAnUploadedCertificateArnInTheRequestPartition() {
        setFormBody("Action=UploadServerCertificate&ServerCertificateName=my-cert");
        assertEquals("arn:aws-cn:iam::000000000000:server-certificate/my-cert",
                iamResource(builder, "cn-north-1", "000000000000"));

        setFormBody("Action=UploadServerCertificate&ServerCertificateName=my-cert");
        assertEquals("arn:aws-us-gov:iam::000000000000:server-certificate/my-cert",
                iamResource(builder, "us-gov-west-1", "000000000000"));
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
        assertEquals(arn, iamResource(stored, "cn-north-1", "000000000000"));

        setFormBody("Action=DeleteServerCertificate&ServerCertificateName=my-cert");
        assertEquals(arn, iamResource(stored, "us-gov-west-1", "000000000000"));
    }

    /** ListServerCertificates names no certificate, nor does a delete leaving its name out: both are on {@code *}. */
    @Test
    void iamFallsBackToTheWildcardWhenNoCertificateIsNamed() {
        setFormBody("Action=ListServerCertificates&PathPrefix=/team/");
        assertEquals("*", iamResource(builder, "us-east-1", "000000000000"));

        setFormBody("Action=DeleteServerCertificate");
        assertEquals("*", iamResource(builder, "us-east-1", "000000000000"));
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

    /**
     * A Resource Groups request is checked on the group its Group names, by its name or its ARN, in the request's
     * partition, from the table; CreateGroup acts on no resource type in the Service Reference and is checked on every
     * resource.
     */
    @Test
    void resourceGroupTargetsAreTheGroupItsNameOrArnNamesFromTheTable() {
        when(ctx.getMethod()).thenReturn("POST");
        request("/get-group");
        for (String region : List.of("us-east-1", "cn-north-1", "us-gov-west-1")) {
            String partition = AwsRegions.partitionFor(region);
            String arn = "arn:" + partition + ":resource-groups:" + region + ":" + ACCOUNT + ":group/pool";
            for (String group : List.of("pool", arn)) {
                setJsonBody("{\"Group\":\"" + group + "\"}");
                assertEquals(List.of(arn), builder.buildResources("resource-groups", "resource-groups:GetGroup", ctx,
                        region, ACCOUNT, partition));
            }
        }
        request("/groups");
        setJsonBody("{\"Name\":\"pool\"}");
        assertEquals(List.of("*"), builder.buildResources("resource-groups", "resource-groups:CreateGroup", ctx,
                "us-east-1", ACCOUNT, "aws"));
    }

    /** A stream call is checked on the stream its group and stream names name, read raw, in the request's partition. */
    @Test
    void logStreamTargetsAreTheStreamTheRequestNamesFromTheTable() {
        for (String action : List.of("logs:CreateLogStream", "logs:DeleteLogStream", "logs:PutLogEvents")) {
            for (String region : List.of("us-east-1", "cn-north-1", "us-gov-west-1")) {
                String partition = AwsRegions.partitionFor(region);
                setJsonBody("{\"logGroupName\":\" /group \",\"logStreamName\":42}");
                assertEquals(List.of("arn:" + partition + ":logs:" + region + ":" + ACCOUNT
                                + ":log-group: /group :log-stream:42"),
                        builder.buildResources("logs", action, ctx, region, ACCOUNT, partition));
            }
        }
    }

    /**
     * A group call is checked on the group its name names, as the Service Reference formats a log group, with no
     * trailing {@code :*}; the evaluator matches a policy naming the group with one for the group's own management
     * actions ({@code LogGroupArnPolicyCompatibilityTest}).
     */
    @Test
    void logGroupTargetsAreTheGroupTheRequestNamesFromTheTable() {
        for (String action : List.of("logs:CreateLogGroup", "logs:DeleteLogGroup", "logs:PutRetentionPolicy",
                "logs:DeleteRetentionPolicy", "logs:TagLogGroup", "logs:FilterLogEvents", "logs:DescribeLogStreams",
                "logs:PutMetricFilter", "logs:StartQuery")) {
            for (String region : List.of("us-east-1", "cn-north-1", "us-gov-west-1")) {
                String partition = AwsRegions.partitionFor(region);
                setJsonBody("{\"logGroupName\":42}");
                assertEquals(List.of("arn:" + partition + ":logs:" + region + ":" + ACCOUNT + ":log-group:42"),
                        builder.buildResources("logs", action, ctx, region, ACCOUNT, partition));
            }
        }
        // A request naming no group names nothing the table reads.
        setJsonBody("{}");
        assertEquals(List.of("*"), builder.buildResources("logs", "logs:FilterLogEvents", ctx, "us-east-1", ACCOUNT,
                "aws"));
    }

    /** A subscription filter is checked on its group, and on its destination when that is a Logs destination. */
    @Test
    void subscriptionFilterTargetsAreItsGroupAndALogsDestination() {
        String prefix = "arn:aws:logs:us-east-1:" + ACCOUNT + ":";
        setJsonBody("{\"logGroupName\":\"app\",\"destinationArn\":\"arn:aws:lambda:us-east-1:" + ACCOUNT
                + ":function:ship\"}");
        assertEquals(List.of(prefix + "log-group:app"),
                builder.buildResources("logs", "logs:PutSubscriptionFilter", ctx, "us-east-1", ACCOUNT, "aws"));
        setJsonBody("{\"logGroupName\":\"app\",\"destinationArn\":\"" + prefix + "destination:central\"}");
        assertEquals(List.of(prefix + "destination:central", prefix + "log-group:app"),
                builder.buildResources("logs", "logs:PutSubscriptionFilter", ctx, "us-east-1", ACCOUNT, "aws"));
    }

    @Test
    void logTagTargetsAreTheArnTheRequestCarries() {
        for (String action : List.of("logs:TagResource", "logs:UntagResource", "logs:ListTagsForResource")) {
            for (String region : List.of("us-east-1", "cn-north-1", "us-gov-west-1", "custom-region-1")) {
                String partition = AwsRegions.partitionFor(region);
                for (String resource : List.of("log-group:group", "destination:central")) {
                    String arn = "arn:" + partition + ":logs:" + region + ":" + ACCOUNT + ":" + resource;
                    setJsonBody("{\"resourceArn\":\"" + arn + "\"}");
                    assertEquals(List.of(arn), builder.buildResources("logs", action, ctx, region, ACCOUNT, partition));
                }
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"aws,us-east-1", "aws-cn,cn-north-1", "aws-us-gov,us-gov-west-1"})
    void putDestinationTargetsTheDestinationItNames(String partition, String region) {
        setJsonBody("{\"destinationName\":\"central\"}");
        assertEquals(List.of("arn:" + partition + ":logs:" + region + ":" + ACCOUNT + ":destination:central"),
                builder.buildResources("logs", "logs:PutDestination", ctx, region, ACCOUNT, partition));
        String targetArn = "arn:" + partition + ":kinesis:" + region + ":" + ACCOUNT + ":stream/central";
        String roleArn = "arn:" + partition + ":iam::" + ACCOUNT + ":role/logs-delivery";
        setJsonBody("{\"destinationName\":\"central\",\"targetArn\":\"" + targetArn
                + "\",\"roleArn\":\"" + roleArn + "\"}");
        assertEquals(List.of("arn:" + partition + ":logs:" + region + ":" + ACCOUNT + ":destination:central"),
                builder.buildResources("logs", "logs:PutDestination", ctx, region, ACCOUNT, partition));
        setJsonBody("{}");
        assertEquals(List.of("*"),
                builder.buildResources("logs", "logs:PutDestination", ctx, region, ACCOUNT, partition));
    }

    @Test
    void deliveryActionsAreAuthorizedOnTheSourceDestinationOrDeliveryTheyName() {
        String prefix = "arn:aws:logs:us-east-1:" + ACCOUNT + ":";
        // A delivery is the one CloudWatch Logs holds under its id; a deleted delivery source is checked on the
        // delivery destinations its deliveries send to, the type the Service Reference gives DeleteDeliverySource.
        CloudWatchLogsDeliveryService deliveries = mock(CloudWatchLogsDeliveryService.class);
        Delivery delivery = new Delivery();
        delivery.setArn(prefix + "delivery:AbCdEfGh12345678");
        when(deliveries.getDelivery("AbCdEfGh12345678", "us-east-1")).thenReturn(delivery);
        when(deliveries.getDelivery("gone", "us-east-1"))
                .thenThrow(new AwsException("ResourceNotFoundException", "Delivery not found", 400));
        when(deliveries.destinationArnsFrom("bus-source", "us-east-1"))
                .thenReturn(List.of(prefix + "delivery-destination:bus-logs"));
        ResourceArnBuilder builder = reading(new CloudWatchLogsStoredResourceArns(null, null, deliveries, null));
        for (String action : List.of("logs:PutDeliverySource", "logs:GetDeliverySource")) {
            setJsonBody("{\"name\":\"bus-source\"}");
            assertEquals(List.of(prefix + "delivery-source:bus-source"),
                    builder.buildResources("logs", action, ctx, "us-east-1", ACCOUNT, "aws"));
        }
        setJsonBody("{\"name\":\"bus-source\"}");
        assertEquals(List.of(prefix + "delivery-destination:bus-logs"),
                builder.buildResources("logs", "logs:DeleteDeliverySource", ctx, "us-east-1", ACCOUNT, "aws"));
        // A source with no deliveries reaches no destination, so the request names none of its resource type.
        setJsonBody("{\"name\":\"idle-source\"}");
        assertEquals(List.of("*"),
                builder.buildResources("logs", "logs:DeleteDeliverySource", ctx, "us-east-1", ACCOUNT, "aws"));
        for (String action : List.of("logs:PutDeliveryDestination", "logs:GetDeliveryDestination",
                "logs:DeleteDeliveryDestination")) {
            setJsonBody("{\"name\":\"bus-logs\"}");
            assertEquals(List.of(prefix + "delivery-destination:bus-logs"),
                    builder.buildResources("logs", action, ctx, "us-east-1", ACCOUNT, "aws"));
        }
        for (String action : List.of("logs:GetDelivery", "logs:DeleteDelivery", "logs:UpdateDeliveryConfiguration")) {
            setJsonBody("{\"id\":\"AbCdEfGh12345678\"}");
            assertEquals(List.of(prefix + "delivery:AbCdEfGh12345678"),
                    builder.buildResources("logs", action, ctx, "us-east-1", ACCOUNT, "aws"));
            // One it holds none of has its id open.
            setJsonBody("{\"id\":\"gone\"}");
            assertEquals(List.of(prefix + "delivery:*"),
                    builder.buildResources("logs", action, ctx, "us-east-1", ACCOUNT, "aws"));
        }
        setJsonBody("{\"deliverySourceName\":\"bus-source\",\"deliveryDestinationArn\":\""
                + prefix + "delivery-destination:bus-logs\"}");
        assertEquals(List.of(prefix + "delivery-destination:bus-logs", prefix + "delivery:*",
                        prefix + "delivery-source:bus-source"),
                builder.buildResources("logs", "logs:CreateDelivery", ctx, "us-east-1", ACCOUNT, "aws"));
        for (String action : List.of("logs:DescribeDeliverySources", "logs:DescribeDeliveryDestinations",
                "logs:DescribeDeliveries")) {
            setJsonBody("{}");
            assertEquals(List.of("*"), builder.buildResources("logs", action, ctx, "us-east-1", ACCOUNT, "aws"));
        }
    }

    // ── STS ──────────────────────────────────────────────────────────────────────

    @Test
    void stsAssumeRoleIsCheckedOnTheRoleItNamesInItsAccountFromTheTable() {
        // STS assumes the role in the account its ARN names, so a role IAM holds there is checked alone.
        setFormBody("Action=AssumeRole&Version=2011-06-15"
                + "&RoleArn=arn%3Aaws%3Aiam%3A%3A729951873967%3Arole%2FAdmin&RoleSessionName=robin");
        assertEquals(List.of("arn:aws:iam::729951873967:role/Admin"), sts("sts:AssumeRole"));
        setFormBody("Action=AssumeRole&Version=2011-06-15"
                + "&RoleArn=arn%3Aaws%3Aiam%3A%3A" + ACCOUNT + "%3Arole%2Fops%2FAdmin&RoleSessionName=robin");
        assertEquals(List.of("arn:aws:iam::" + ACCOUNT + ":role/ops/Admin"), sts("sts:AssumeRole"));
    }

    @Test
    void stsAssumeRoleOfARoleNoAccountHoldsIsCheckedAsTheCallersToo() {
        setFormBody("Action=AssumeRole&Version=2011-06-15"
                + "&RoleArn=arn%3Aaws%3Aiam%3A%3A729951873967%3Arole%2FMissing&RoleSessionName=robin");
        assertEquals(List.of("arn:aws:iam::729951873967:role/Missing", "arn:aws:iam::" + ACCOUNT + ":role/Missing"),
                sts("sts:AssumeRole"));
    }

    @Test
    void stsAssumeRoleIgnoresARoleArnInTheUrlOfAFormPost() {
        // The handler acts on the body's RoleArn, so authorization must check that one, never a
        // decoy in the query string.
        queryParams.add("RoleArn", "arn:aws:iam::729951873967:role/Allowed");
        setFormBody("Action=AssumeRole&Version=2011-06-15"
                + "&RoleArn=arn%3Aaws%3Aiam%3A%3A729951873967%3Arole%2FAdmin&RoleSessionName=robin");
        assertEquals(List.of("arn:aws:iam::729951873967:role/Admin"), sts("sts:AssumeRole"));
    }

    @Test
    void stsGetFederationTokenIsCheckedOnTheFederatedUserItNamesNotARoleArnItNeverReads() {
        // GetFederationToken never acts on a role, so a RoleArn added to it must not let a policy
        // scoped to that role allow it.
        setFormBody("Action=GetFederationToken&Version=2011-06-15&Name=robin"
                + "&RoleArn=arn%3Aaws%3Aiam%3A%3A729951873967%3Arole%2FAdmin");
        assertEquals(List.of("arn:aws:sts::" + ACCOUNT + ":federated-user/robin"), sts("sts:GetFederationToken"));
    }

    @Test
    void stsMalformedRoleEncodingIsRejectedBeforeWildcardAuthorization() {
        setFormBody("Action=AssumeRole&Version=2011-06-15&RoleArn=arn%3Aaws%ZZ&RoleSessionName=robin");
        WebApplicationException error = assertThrows(WebApplicationException.class, () -> sts("sts:AssumeRole"));
        assertEquals(404, error.getResponse().getStatus());
    }

    @Test
    void stsRoleArnFittingNoRoleFormatNamesNoRole() {
        // The handler does not trim, and neither does the reader: a value with a leading space fits no
        // role's ARN format, so the request is checked on every resource, which a policy naming the role
        // does not match.
        setFormBody("Action=AssumeRole&Version=2011-06-15"
                + "&RoleArn=+arn%3Aaws%3Aiam%3A%3A729951873967%3Arole%2FAdmin&RoleSessionName=robin");
        assertEquals(List.of("*"), sts("sts:AssumeRole"));
    }

    private List<String> sts(String operation) {
        IamService iam = mock(IamService.class);
        when(iam.findRole(anyString(), anyString())).thenReturn(Optional.empty());
        when(iam.findRole("729951873967", "Admin")).thenReturn(Optional.of(new IamRole()));
        return reading(new StsStoredResourceArns(iam)).buildResources("sts", operation, ctx, "us-east-1", ACCOUNT,
                "aws");
    }


    // ── DynamoDB ─────────────────────────────────────────────────────────────────

    @Test
    void listTablesUsesTheCollectionInTheRequestPartitionRegionAndAccount() {
        // The collection AWS was measured to check ListTables on, the Service Reference giving its action no type.
        for (String body : List.of("{}", "{\"ExclusiveStartTableName\":\"page-start\"}",
                "{\"TableName\":\"decoy\",\"ResourceArn\":\"decoy\"}")) {
            setJsonBody(body);
            assertEquals(List.of("arn:aws-cn:dynamodb:cn-north-1:123456789012:table/*"),
                    builder.buildResources("dynamodb", "dynamodb:ListTables", ctx,
                            "cn-north-1", "123456789012", "aws-cn"));
        }
    }

    @Test
    void listTablesDoesNotChangeOtherTablelessOperations() {
        setJsonBody("{}");
        assertEquals(List.of("*"), builder.buildResources("dynamodb", "dynamodb:DescribeLimits",
                ctx, "us-east-1", ACCOUNT, "aws"));
        setJsonBody("{\"TableName\":\"Orders\"}");
        assertEquals(List.of("arn:aws:dynamodb:us-east-1:000000000000:table/Orders"),
                builder.buildResources("dynamodb", "dynamodb:DescribeTable",
                        ctx, "us-east-1", ACCOUNT, "aws"));
    }

    private static final String DDB = "arn:aws:dynamodb:us-east-1:000000000000:table/";

    /** The resources a DynamoDB request is checked on, as the table reads {@code operation}. */
    private List<String> dynamoDb(String operation) {
        return builder.buildResources("dynamodb", "dynamodb:" + operation, ctx, "us-east-1", ACCOUNT, "aws");
    }

    /** The checks of a DynamoDB request checked by its parts, each statement parsed as DynamoDB parses it. */
    private List<RequestResources.Target> dynamoDbTargets(String operation) {
        return reading(new DynamoDbStoredResourceArns(mock(DynamoDbTableAccess.class)))
                .targets("dynamodb", "dynamodb:" + operation, ctx, "us-east-1", ACCOUNT, "aws");
    }

    @Test
    void dynamoDbChecksTheTableItsRequestNames() {
        setJsonBody("{\"TableName\":\"FgacTable\"}");
        assertEquals(List.of(DDB + "FgacTable"), dynamoDb("GetItem"));
        // A bare name that merely looks ARN-ish is still a name, not an ARN.
        setJsonBody("{\"TableName\":\"arn:aws:dynamodb:us-east-1\"}");
        assertEquals(List.of(DDB + "arn:aws:dynamodb:us-east-1"), dynamoDb("GetItem"));
        setJsonBody("{\"TableName\":\"" + DDB + "FgacTable\"}");
        assertEquals(List.of(DDB + "FgacTable"), dynamoDb("GetItem"));
        setJsonBody("{\"TableCreationParameters\":{\"TableName\":\"ImportTarget\"},\"InputFormat\":\"DYNAMODB_JSON\"}");
        assertEquals(List.of(DDB + "ImportTarget"), dynamoDb("ImportTable"));
        setJsonBody("{}");
        assertEquals(List.of("*"), dynamoDb("GetItem"));
    }

    /**
     * A table ARN of any partition is checked as given, and as the caller's table of that name, which DynamoDB acts on:
     * a {@code startsWith("arn:aws:dynamodb:")} probe once rebuilt another partition's ARN as {@code table/arn:...}.
     */
    @Test
    void dynamoDbChecksATableArnOfAnyPartitionAsGiven() {
        for (String fullArn : List.of("arn:aws-us-gov:dynamodb:us-gov-west-1:000000000000:table/FgacTable",
                "arn:aws-cn:dynamodb:cn-north-1:000000000000:table/FgacTable")) {
            setJsonBody("{\"TableName\":\"" + fullArn + "\"}");
            List<String> checked = dynamoDb("GetItem");
            assertEquals(fullArn, checked.getFirst());
            assertTrue(checked.contains(DDB + "FgacTable"), checked.toString());
        }
    }

    @Test
    void dynamoDbChecksTheResourceAnArnMemberHolds() {
        setJsonBody("{\"ResourceArn\":\"" + DDB + "FgacTable\",\"Tags\":[{\"Key\":\"env\",\"Value\":\"dev\"}]}");
        assertEquals(List.of(DDB + "FgacTable"), dynamoDb("TagResource"));
        setJsonBody("{\"TableArn\":\"" + DDB + "ExportTable\",\"S3Bucket\":\"my-bucket\"}");
        assertEquals(List.of(DDB + "ExportTable"), dynamoDb("ExportTableToPointInTime"));
        String stream = DDB + "FgacTable/stream/2026-09-01T00:00:00.000";
        setJsonBody("{\"StreamArn\":\"" + stream + "\"}");
        assertEquals(List.of(stream), dynamoDb("DescribeStream"));
        String export = DDB + "FgacTable/export/01693526400000-abcd";
        setJsonBody("{\"ExportArn\":\"" + export + "\"}");
        assertEquals(List.of(export), dynamoDb("DescribeExport"));
    }

    @Test
    void dynamoDbChecksThePointInTimeRestoreOnItsSourceTable() {
        setJsonBody("{\"SourceTableName\":\"Music\",\"TargetTableName\":\"MusicCopy\"}");
        assertEquals(List.of(DDB + "Music"), dynamoDb("RestoreTableToPointInTime"));
        String source = "arn:aws:dynamodb:eu-west-2:000000000000:table/Music";
        setJsonBody("{\"SourceTableArn\":\"" + source + "\",\"TargetTableName\":\"MusicCopy\"}");
        assertEquals(source, dynamoDb("RestoreTableToPointInTime").getFirst());
    }

    /** A batch's tables are the keys of its RequestItems, which botocore keys by the shape a table's name has. */
    @Test
    void dynamoDbChecksABatchOnEveryTableItsRequestItemsKey() {
        setJsonBody("{\"RequestItems\":{\"MyTable\":{\"Keys\":[]}}}");
        assertEquals(List.of(DDB + "MyTable"), dynamoDb("BatchGetItem"));
        setJsonBody("{\"RequestItems\":{\"TableA\":{\"Keys\":[]},\"TableB\":{\"Keys\":[]}}}");
        assertEquals(List.of(DDB + "TableA", DDB + "TableB"), dynamoDb("BatchGetItem"));
        assertEquals(List.of(DDB + "TableA", DDB + "TableB"), dynamoDb("BatchWriteItem"));
    }

    /**
     * Each of a transaction's items is checked as the action the developer guide gives its kind, on its own table,
     * with the item it comes from: {@code Put} as {@code dynamodb:PutItem}, {@code ConditionCheck} as
     * {@code dynamodb:ConditionCheckItem}, {@code Get} as {@code dynamodb:GetItem}; the transaction's own action never.
     */
    @Test
    void dynamoDbChecksEachTransactionItemAsItsKindsAction() {
        setJsonBody("{\"TransactItems\":[{\"Put\":{\"TableName\":\"TableA\"}},"
                + "{\"ConditionCheck\":{\"TableName\":\"TableB\"}},{\"Delete\":{\"TableName\":\"TableA\"}}]}");
        assertTrue(RequestResources.checkedByParts("dynamodb:TransactWriteItems"));
        List<RequestResources.Target> targets = dynamoDbTargets("TransactWriteItems");
        assertEquals(List.of("dynamodb:ConditionCheckItem", "dynamodb:PutItem", "dynamodb:DeleteItem"),
                targets.stream().map(RequestResources.Target::action).toList());
        assertEquals(List.of(List.of(DDB + "TableB"), List.of(DDB + "TableA"), List.of(DDB + "TableA")),
                targets.stream().map(RequestResources.Target::resources).toList());
        assertEquals("TableB", targets.getFirst().element().path("TableName").asText());
        setJsonBody("{\"TransactItems\":[{\"Get\":{\"TableName\":\"MyTable\"}}]}");
        assertEquals(List.of(new RequestResources.Target("dynamodb:GetItem", List.of(DDB + "MyTable"),
                        dynamoDbTargets("TransactGetItems").getFirst().element())),
                dynamoDbTargets("TransactGetItems"));
        assertEquals(List.of(), dynamoDbTargets("GetItem"));
    }

    /**
     * A PartiQL statement is checked as the action the developer guide gives its verb, on the table or index it names,
     * as DynamoDB parses it, a quoted keyword or name included; each statement of a batch or transaction apart.
     */
    @Test
    void dynamoDbChecksEachPartiQLStatementAsItsVerbsAction() {
        Map<String, String> statements = new LinkedHashMap<>();
        statements.put("SELECT * FROM \\\"UsersTable\\\" WHERE id = '1'", "dynamodb:PartiQLSelect UsersTable");
        statements.put("INSERT INTO OrdersTable VALUE {'id': '1'}", "dynamodb:PartiQLInsert OrdersTable");
        statements.put("UPDATE \\\"ProductsTable\\\" SET price=10 WHERE id='1'",
                "dynamodb:PartiQLUpdate ProductsTable");
        statements.put("DELETE FROM \\\"LogsTable\\\" WHERE id='1'", "dynamodb:PartiQLDelete LogsTable");
        statements.put("SELECT * FROM \\\"Table With Spaces & Special:Chars\\\" WHERE id = '1'",
                "dynamodb:PartiQLSelect Table With Spaces & Special:Chars");
        statements.put("SELECT \\\"FROM TableEvil\\\" FROM \\\"TableA\\\" WHERE pk = 1",
                "dynamodb:PartiQLSelect TableA");
        statements.forEach((statement, expected) -> {
            setJsonBody("{\"Statement\":\"" + statement + "\"}");
            List<RequestResources.Target> targets = dynamoDbTargets("ExecuteStatement");
            String[] parts = expected.split(" ", 2);
            assertEquals(List.of(new RequestResources.Target(parts[0], List.of(DDB + parts[1]), null)), targets,
                    statement);
        });
        setJsonBody("{\"Statement\":\"SELECT * FROM \\\"Music\\\".\\\"byArtist\\\" WHERE a = 'x'\"}");
        assertEquals(List.of(new RequestResources.Target("dynamodb:PartiQLSelect",
                List.of(DDB + "Music/index/byArtist"), null)), dynamoDbTargets("ExecuteStatement"));
        setJsonBody("{\"Statements\":[{\"Statement\":\"SELECT * FROM \\\"TableA\\\" WHERE id = '1'\"},"
                + "{\"Statement\":\"DELETE FROM TableB WHERE id = '2'\"}]}");
        List<RequestResources.Target> batch = dynamoDbTargets("BatchExecuteStatement");
        assertEquals(List.of("dynamodb:PartiQLSelect TableA", "dynamodb:PartiQLDelete TableB"), batch.stream()
                .map(target -> target.action() + " " + target.resources().getFirst().substring(DDB.length())).toList());
        assertTrue(batch.getFirst().element().path("Statement").asText().contains("TableA"));
        setJsonBody("{\"TransactStatements\":[{\"Statement\":\"INSERT INTO \\\"TableA\\\" VALUE {'id': '1'}\"},"
                + "{\"Statement\":\"UPDATE \\\"TableB\\\" SET x=1 WHERE id='2'\"}]}");
        assertEquals(List.of("dynamodb:PartiQLInsert", "dynamodb:PartiQLUpdate"),
                dynamoDbTargets("ExecuteTransaction").stream().map(RequestResources.Target::action).sorted().toList());
        setJsonBody("{\"TransactStatements\":[{\"Statement\":\"INSERT INTO \\\"TableA\\\" VALUE {'id': '1'}\"},"
                + "{\"Statement\":\"EXISTS(SELECT * FROM \\\"TableB\\\" WHERE id = ? AND x = 1)\"}]}");
        assertEquals(List.of("dynamodb:PartiQLSelect TableB", "dynamodb:PartiQLInsert TableA"),
                dynamoDbTargets("ExecuteTransaction").stream().map(target -> target.action() + " "
                        + target.resources().getFirst().substring(DDB.length())).toList());
        setJsonBody("{\"Statements\":[{\"Statement\":\"-- nothing\"}]}");
        assertEquals(List.of(), dynamoDbTargets("BatchExecuteStatement"));
    }

    @Test
    void entityStreamRemainsReadableAfterResourceArnBuilding() throws IOException {
        String json = "{\"TableName\":\"FgacTable\",\"Item\":{\"PK\":{\"S\":\"USER_1\"}}}";
        setJsonBody(json);

        assertEquals(List.of(DDB + "FgacTable"), dynamoDb("PutItem"));

        InputStream entityStream = ctx.getEntityStream();
        assertNotNull(entityStream);
        byte[] readBack = entityStream.readAllBytes();
        assertEquals(json, new String(readBack, StandardCharsets.UTF_8));
    }

    // ── Kinesis ──────────────────────────────────────────────────────────────────

    /** A Kinesis request is checked on the stream its StreamName or StreamARN names, read from the generated table. */
    @Test
    void kinesisChecksTheStreamItsNameOrArnNames() {
        setJsonBody("{\"StreamName\":\"order-events\"}");
        assertEquals(List.of("arn:aws:kinesis:us-east-1:000000000000:stream/order-events"),
                builder.buildResources("kinesis", "kinesis:PutRecord", ctx, "us-east-1", ACCOUNT, "aws"));
        String fullArn = "arn:aws:kinesis:us-east-1:000000000000:stream/order-events";
        setJsonBody("{\"StreamARN\":\"" + fullArn + "\"}");
        assertEquals(List.of(fullArn),
                builder.buildResources("kinesis", "kinesis:DescribeStreamSummary", ctx, "us-east-1", ACCOUNT, "aws"));
    }

    /** A consumer is checked on its ARN, and a tag call on the resource its ResourceARN names. */
    @Test
    void kinesisChecksAConsumerAndATaggedResourceOnTheirArns() {
        String consumer = "arn:aws:kinesis:us-east-1:000000000000:stream/order-events/consumer/reader:1700000000";
        setJsonBody("{\"ConsumerARN\":\"" + consumer + "\"}");
        assertEquals(List.of(consumer),
                builder.buildResources("kinesis", "kinesis:DescribeStreamConsumer", ctx, "us-east-1", ACCOUNT, "aws"));
        setJsonBody("{\"ResourceARN\":\"" + consumer + "\",\"Tags\":{}}");
        assertEquals(List.of(consumer),
                builder.buildResources("kinesis", "kinesis:TagResource", ctx, "us-east-1", ACCOUNT, "aws"));
    }

    // ── Secrets Manager ──────────────────────────────────────────────────────────

    private static final String SECRET = "arn:aws:secretsmanager:us-east-1:000000000000:secret:";

    /**
     * Secrets Manager as IAM asks it: the ARN of each secret it holds, by name, or by an ARN, a partial one included,
     * in whatever Region or account it names.
     */
    private static StoredResourceArns secretsHolding(Map<String, String> stored) {
        return new StoredResourceArns() {
            @Override
            public String namespace() {
                return "secretsmanager";
            }

            @Override
            public Optional<String> find(String type, List<String> names, String region) {
                return "Secret".equals(type) && names.size() == 1
                        ? Optional.ofNullable(stored.get(names.getFirst())) : Optional.empty();
            }

            @Override
            public Optional<String> resolveStoredArn(String arn) {
                return Optional.ofNullable(stored.get(arn));
            }
        };
    }

    private List<String> secretsResources(ResourceArnBuilder builder, String operation, String region,
                                          String partition) {
        return builder.buildResources("secretsmanager", operation, ctx, region, ACCOUNT, partition);
    }

    /**
     * A secret named by its name or an ARN, a partial one included, is the ARN Secrets Manager holds it under, random
     * suffix included; one it does not hold has the six characters botocore documents open ({@code -??????}), and an
     * ARN it does not hold is as given.
     */
    @Test
    void aSecretIsTheArnSecretsManagerHoldsItUnderOrItsNameWithTheSuffixOpen() {
        String stored = SECRET + "app/core-AbC123";
        ResourceArnBuilder backed = reading(secretsHolding(Map.of("app/core", stored, SECRET + "app/core", stored)));

        setJsonBody("{\"SecretId\":\"app/core\"}");
        assertEquals(List.of(stored), secretsResources(backed, "secretsmanager:GetSecretValue", "us-east-1", "aws"));
        setJsonBody("{\"SecretId\":\"" + SECRET + "app/core\"}");
        assertEquals(List.of(stored), secretsResources(backed, "secretsmanager:GetSecretValue", "us-east-1", "aws"));
        setJsonBody("{\"SecretId\":\"app/missing\"}");
        assertEquals(List.of(SECRET + "app/missing-??????"),
                secretsResources(backed, "secretsmanager:DescribeSecret", "us-east-1", "aws"));
        setJsonBody("{\"SecretId\":\"" + SECRET + "app/missing\"}");
        assertEquals(List.of(SECRET + "app/missing"),
                secretsResources(backed, "secretsmanager:DescribeSecret", "us-east-1", "aws"));
    }

    /**
     * {@code CreateSecret} names the secret it makes by {@code Name}, its suffix not minted yet, and every other
     * operation by {@code SecretId}, so a member the serving operation does not read names nothing; an operation
     * acting on no resource type is checked on {@code *}.
     */
    @Test
    void aCreateNamesTheSecretItMakesAndEveryOtherOperationTheOneItsSecretIdNames() {
        ResourceArnBuilder backed = reading(secretsHolding(Map.of("app/existing", SECRET + "app/existing-AbC123")));

        setJsonBody("{\"Name\":\"app/new\",\"SecretId\":\"app/existing\",\"SecretString\":\"x\"}");
        assertEquals(List.of(SECRET + "app/new-??????"),
                secretsResources(backed, "secretsmanager:CreateSecret", "us-east-1", "aws"));
        setJsonBody("{\"Name\":\"app/new\"}");
        assertEquals(List.of("*"), secretsResources(backed, "secretsmanager:GetSecretValue", "us-east-1", "aws"));
        setJsonBody("{}");
        assertEquals(List.of("*"), secretsResources(backed, "secretsmanager:ListSecrets", "us-east-1", "aws"));
    }

    /**
     * A secret Secrets Manager holds in another Region than the request's, named by its ARN, partial or full, is the
     * one its operations act on there, and so is one another account holds under the full ARN named: that ARN alone,
     * no secret of its name in the request's Region or the caller's account.
     */
    @Test
    void aSecretHeldInAnotherRegionOrAccountIsCheckedOnItsArnAlone() {
        String west = "arn:aws:secretsmanager:us-west-2:000000000000:secret:";
        String stored = west + "app/core-AbC123";
        String shared = "arn:aws:secretsmanager:us-east-1:111122223333:secret:app/shared-XyZ789";
        ResourceArnBuilder backed = reading(secretsHolding(Map.of(west + "app/core", stored, stored, stored,
                shared, shared)));

        setJsonBody("{\"SecretId\":\"" + west + "app/core\"}");
        assertEquals(List.of(stored), secretsResources(backed, "secretsmanager:GetSecretValue", "us-east-1", "aws"));
        setJsonBody("{\"SecretId\":\"" + stored + "\"}");
        assertEquals(List.of(stored), secretsResources(backed, "secretsmanager:GetSecretValue", "us-east-1", "aws"));
        setJsonBody("{\"SecretId\":\"" + shared + "\"}");
        assertEquals(List.of(shared), secretsResources(backed, "secretsmanager:GetSecretValue", "us-east-1", "aws"));
    }

    @Test
    void aSecretsArnIsInTheRequestsPartition() {
        setJsonBody("{\"Name\":\"app/core\"}");
        assertEquals(List.of("arn:aws-cn:secretsmanager:polygondwanaland-west-1:000000000000:secret:app/core-??????"),
                secretsResources(reading(secretsHolding(Map.of())), "secretsmanager:CreateSecret",
                        "polygondwanaland-west-1", "aws-cn"));
    }

    // ── KMS ──────────────────────────────────────────────────────────────────────

    private static final String KMS_KEY_ID = "1234abcd-12ab-34cd-56ef-1234567890ab";
    private static final String KMS_KEY_ARN = "arn:aws:kms:us-east-1:000000000000:key/" + KMS_KEY_ID;
    private static final String KMS_OTHER_KEY_ARN = "arn:aws:kms:us-east-1:000000000000:key/5678efab-34cd-12ab-90ef-1234567890ab";
    private static final String KMS_ALIAS_ARN = "arn:aws:kms:us-east-1:000000000000:alias/app";

    /** A builder reading {@code kms} as KMS's stored ARNs answer from it, in the caller's account. */
    private static ResourceArnBuilder backedBy(KmsService kms) {
        RegionResolver regions = mock(RegionResolver.class);
        when(regions.getAccountId()).thenReturn(ACCOUNT);
        return reading(new KmsStoredResourceArns(kms, regions));
    }

    /** The resources a KMS request is checked on, as the table reads {@code operation}. */
    private List<String> kms(ResourceArnBuilder builder, String operation) {
        return builder.buildResources("kms", "kms:" + operation, ctx, "us-east-1", ACCOUNT, "aws");
    }

    /** A builder reading the stored ARNs of a namespace, for every request of that namespace. */
    private static ResourceArnBuilder reading(StoredResourceArns stored) {
        @SuppressWarnings("unchecked")
        Instance<StoredResourceArns> instance = mock(Instance.class);
        when(instance.iterator()).thenAnswer(invocation -> List.of(stored).iterator());
        return new ResourceArnBuilder(new ObjectMapper(), instance);
    }

    /** The finder of the stored ARNs of a namespace, answering one name of one type with one ARN. */
    private static StoredResourceArns stored(String namespace, String type, String name, String arn) {
        return new StoredResourceArns() {
            @Override
            public String namespace() {
                return namespace;
            }

            @Override
            public Optional<String> find(String found, List<String> wanted, String region) {
                return type.equals(found) && List.of(name).equals(wanted) ? Optional.of(arn) : Optional.empty();
            }
        };
    }

    /**
     * The gap issue 6256 describes. KMS is a JSON protocol on {@code /} with the key in the body, so reading a
     * {@code /keys/<id>} path segment named nothing and every call was evaluated against {@code key/*}. A key-scoped
     * operation is checked on the key its request names, an alias name or ARN resolved to the key it points at, in
     * whatever state, as KMS's handler resolves it.
     */
    @Test
    void aKmsKeyScopedOperationIsCheckedOnTheKeyItsRequestNames() {
        KmsService kms = mock(KmsService.class);
        when(kms.findKeyArnOfAccount(any(), any(), any())).thenReturn(Optional.empty());
        when(kms.findKeyArnOfAccount(KMS_KEY_ID, ACCOUNT, "us-east-1")).thenReturn(Optional.of(KMS_KEY_ARN));
        when(kms.findKeyArnOfAccount("alias/app", ACCOUNT, "us-east-1")).thenReturn(Optional.of(KMS_KEY_ARN));
        when(kms.findKeyArnOfAccount(KMS_ALIAS_ARN, ACCOUNT, "us-east-1")).thenReturn(Optional.of(KMS_KEY_ARN));
        ResourceArnBuilder backed = backedBy(kms);
        for (String operation : List.of("Encrypt", "Decrypt", "GenerateDataKey", "GenerateDataKeyWithoutPlaintext",
                "DescribeKey", "GetPublicKey", "ScheduleKeyDeletion", "CancelKeyDeletion", "CreateGrant", "ListGrants",
                "Sign", "Verify", "GenerateMac", "VerifyMac", "TagResource", "UntagResource", "ListResourceTags",
                "GetKeyPolicy", "PutKeyPolicy", "ListKeyPolicies", "UpdateKeyDescription", "EnableKey", "DisableKey",
                "EnableKeyRotation", "DisableKeyRotation", "GetKeyRotationStatus", "RotateKeyOnDemand",
                "GetParametersForImport", "ImportKeyMaterial", "DeleteImportedKeyMaterial", "RevokeGrant")) {
            setJsonBody("{\"KeyId\":\"" + KMS_KEY_ID + "\"}");
            assertEquals(List.of(KMS_KEY_ARN), kms(backed, operation), operation);
        }
        for (String named : List.of("alias/app", KMS_ALIAS_ARN, KMS_KEY_ARN)) {
            setJsonBody("{\"KeyId\":\"" + named + "\",\"Plaintext\":\"aGk=\"}");
            assertEquals(List.of(KMS_KEY_ARN), kms(backed, "Encrypt"), named);
        }
    }

    /** A key KMS holds none of is checked on its id in the request's partition, as KMS mints a key there. */
    @Test
    void aKmsKeyNoneHoldsIsCheckedOnItsIdInTheRequestsPartition() {
        KmsService kms = mock(KmsService.class);
        when(kms.findKeyArnOfAccount(any(), any(), any())).thenReturn(Optional.empty());
        setJsonBody("{\"KeyId\":\"" + KMS_KEY_ID + "\"}");
        assertEquals(List.of("arn:aws-cn:kms:cn-north-1:000000000000:key/" + KMS_KEY_ID),
                backedBy(kms).buildResources("kms", "kms:Encrypt", ctx, "cn-north-1", ACCOUNT, "aws-cn"));
    }

    /**
     * The calls whose actions the Service Reference gives no resource type, the custom key store ones among them,
     * are checked on {@code *}, as AWS checks them.
     */
    @Test
    void theKmsCallsThatNameNoKeyAreCheckedOnStar() {
        ResourceArnBuilder backed = backedBy(mock(KmsService.class));
        setJsonBody("{\"CustomKeyStoreId\":\"cks-1234567890abcdef0\"}");
        for (String operation : List.of("CreateKey", "GenerateRandom", "ListKeys", "ListAliases",
                "ListRetirableGrants", "ConnectCustomKeyStore", "DescribeCustomKeyStores", "DeleteCustomKeyStore")) {
            assertEquals(List.of("*"), kms(backed, operation), operation);
        }
    }

    /**
     * A Decrypt naming no key is checked on the key its ciphertext was encrypted under, which the API reference says
     * KMS reads from the blob's metadata; one naming a key on that key; one whose blob names none KMS holds on
     * {@code *}.
     */
    @Test
    void decryptIsCheckedOnTheKeyOfItsCiphertextWhenItNamesNone() {
        byte[] blob = "ciphertext".getBytes(StandardCharsets.UTF_8);
        String encoded = Base64.getEncoder().encodeToString(blob);
        KmsService kms = mock(KmsService.class);
        when(kms.findKeyArnOfAccount(any(), any(), any())).thenReturn(Optional.empty());
        when(kms.findKeyArnOfAccount("alias/app", ACCOUNT, "us-east-1")).thenReturn(Optional.of(KMS_OTHER_KEY_ARN));
        when(kms.decryptToKeyArn(blob, "us-east-1")).thenReturn(KMS_KEY_ARN);
        ResourceArnBuilder backed = backedBy(kms);

        setJsonBody("{\"CiphertextBlob\":\"" + encoded + "\"}");
        assertEquals(List.of(KMS_KEY_ARN), kms(backed, "Decrypt"));
        setJsonBody("{\"CiphertextBlob\":\"" + encoded + "\",\"KeyId\":\"alias/app\"}");
        assertEquals(List.of(KMS_OTHER_KEY_ARN), kms(backed, "Decrypt"));
        setJsonBody("{\"CiphertextBlob\":\"not base64!\"}");
        assertEquals(List.of("*"), kms(backed, "Decrypt"));
    }

    /**
     * The alias calls are checked on the alias and the keys the developer guide's permissions table names: CreateAlias
     * the key it associates, UpdateAlias the newly and the currently associated key, DeleteAlias the key its alias is
     * associated with.
     */
    @Test
    void theKmsAliasCallsAreCheckedOnTheAliasAndItsKeys() {
        KmsService kms = mock(KmsService.class);
        when(kms.findKeyArnOfAccount(any(), any(), any())).thenReturn(Optional.empty());
        when(kms.findKeyArnOfAccount(KMS_KEY_ID, ACCOUNT, "us-east-1")).thenReturn(Optional.of(KMS_KEY_ARN));
        when(kms.findKeyArn("alias/app", "us-east-1")).thenReturn(Optional.of(KMS_OTHER_KEY_ARN));
        ResourceArnBuilder backed = backedBy(kms);

        setJsonBody("{\"AliasName\":\"alias/app\",\"TargetKeyId\":\"" + KMS_KEY_ID + "\"}");
        assertEquals(List.of(KMS_ALIAS_ARN, KMS_KEY_ARN), kms(backed, "CreateAlias"));
        assertEquals(List.of(KMS_ALIAS_ARN, KMS_KEY_ARN, KMS_OTHER_KEY_ARN), kms(backed, "UpdateAlias"));
        setJsonBody("{\"AliasName\":\"alias/app\"}");
        assertEquals(List.of(KMS_ALIAS_ARN, KMS_OTHER_KEY_ARN), kms(backed, "DeleteAlias"));
    }

    /**
     * ReEncrypt is checked as the two actions the permissions table gives it, never as its own name:
     * {@code kms:ReEncryptFrom} on the key it decrypts with (its {@code SourceKeyId}, or else the key of its
     * ciphertext) and {@code kms:ReEncryptTo} on the key it encrypts with.
     */
    @Test
    void reEncryptIsCheckedAsReEncryptFromOnItsSourceAndReEncryptToOnItsDestination() {
        byte[] blob = "ciphertext".getBytes(StandardCharsets.UTF_8);
        String encoded = Base64.getEncoder().encodeToString(blob);
        KmsService kms = mock(KmsService.class);
        when(kms.findKeyArnOfAccount(any(), any(), any())).thenReturn(Optional.empty());
        when(kms.findKeyArnOfAccount("alias/source", ACCOUNT, "us-east-1")).thenReturn(Optional.of(KMS_OTHER_KEY_ARN));
        when(kms.findKeyArnOfAccount("alias/destination", ACCOUNT, "us-east-1")).thenReturn(Optional.of(KMS_KEY_ARN));
        when(kms.decryptToKeyArn(blob, "us-east-1")).thenReturn(KMS_OTHER_KEY_ARN);
        ResourceArnBuilder backed = backedBy(kms);

        setJsonBody("{\"CiphertextBlob\":\"" + encoded + "\",\"DestinationKeyId\":\"alias/destination\"}");
        assertEquals(List.of(new RequestResources.Target("kms:ReEncryptFrom", List.of(KMS_OTHER_KEY_ARN), null),
                        new RequestResources.Target("kms:ReEncryptTo", List.of(KMS_KEY_ARN), null)),
                backed.targets("kms", "kms:ReEncrypt", ctx, "us-east-1", ACCOUNT, "aws"));
        setJsonBody("{\"CiphertextBlob\":\"" + encoded + "\",\"SourceKeyId\":\"alias/source\","
                + "\"DestinationKeyId\":\"alias/destination\"}");
        assertEquals(List.of(new RequestResources.Target("kms:ReEncryptFrom", List.of(KMS_OTHER_KEY_ARN), null),
                        new RequestResources.Target("kms:ReEncryptTo", List.of(KMS_KEY_ARN), null)),
                backed.targets("kms", "kms:ReEncrypt", ctx, "us-east-1", ACCOUNT, "aws"));
        // A source no member names, nor the ciphertext, is not checked; the destination is.
        setJsonBody("{\"CiphertextBlob\":\"not base64!\",\"DestinationKeyId\":\"alias/destination\"}");
        assertEquals(List.of(new RequestResources.Target("kms:ReEncryptTo", List.of(KMS_KEY_ARN), null)),
                backed.targets("kms", "kms:ReEncrypt", ctx, "us-east-1", ACCOUNT, "aws"));
        assertEquals(List.of("*"), kms(backed, "ReEncrypt"));
        assertEquals(List.of(), backed.targets("kms", "kms:Encrypt", ctx, "us-east-1", ACCOUNT, "aws"));
    }

    /**
     * ReplicateKey is checked as the two permissions the table gives it: {@code kms:ReplicateKey} on the primary key
     * and {@code kms:CreateKey} on no resource.
     */
    @Test
    void replicateKeyIsCheckedAsReplicateKeyOnItsKeyAndCreateKeyOnStar() {
        KmsService kms = mock(KmsService.class);
        when(kms.findKeyArnOfAccount(KMS_KEY_ID, ACCOUNT, "us-east-1")).thenReturn(Optional.of(KMS_KEY_ARN));
        setJsonBody("{\"KeyId\":\"" + KMS_KEY_ID + "\",\"ReplicaRegion\":\"us-west-2\"}");
        assertEquals(List.of(new RequestResources.Target("kms:ReplicateKey", List.of(KMS_KEY_ARN), null),
                        new RequestResources.Target("kms:CreateKey", List.of("*"), null)),
                backedBy(kms).targets("kms", "kms:ReplicateKey", ctx, "us-east-1", ACCOUNT, "aws"));
    }

    /**
     * The grant calls are checked on the key they name and on the key the grant their {@code GrantId} names is on,
     * which closes naming one key while revoking another key's grant. A RetireGrant naming its grant by token alone
     * names neither, as botocore's record of a grant holds no token: it is checked on {@code *}.
     */
    @Test
    void theKmsGrantCallsAreCheckedOnTheirKeyAndTheKeyOfTheirGrant() {
        KmsService kms = mock(KmsService.class);
        when(kms.findKeyArnOfAccount(any(), any(), any())).thenReturn(Optional.empty());
        when(kms.findKeyArnOfAccount(KMS_KEY_ID, ACCOUNT, "us-east-1")).thenReturn(Optional.of(KMS_KEY_ARN));
        when(kms.findKeyArnOfAccount("other-key", ACCOUNT, "us-east-1")).thenReturn(Optional.of(KMS_OTHER_KEY_ARN));
        when(kms.findGrantKeyArn(null, "grant-id", "us-east-1")).thenReturn(Optional.of(KMS_KEY_ARN));
        ResourceArnBuilder backed = backedBy(kms);

        setJsonBody("{\"KeyId\":\"other-key\",\"GrantId\":\"grant-id\"}");
        assertEquals(List.of(KMS_OTHER_KEY_ARN, KMS_KEY_ARN), kms(backed, "RevokeGrant"));
        assertEquals(List.of(KMS_OTHER_KEY_ARN, KMS_KEY_ARN), kms(backed, "RetireGrant"));
        setJsonBody("{\"KeyId\":\"" + KMS_KEY_ID + "\",\"GrantId\":\"grant-id\"}");
        assertEquals(List.of(KMS_KEY_ARN), kms(backed, "RevokeGrant"));
        setJsonBody("{\"GrantToken\":\"grant-token\"}");
        assertEquals(List.of("*"), kms(backed, "RetireGrant"));
    }

    // ── SSM ──────────────────────────────────────────────────────────────────────

    @Test
    void ssmNamesTheParameterItsNameNamesWithOrWithoutALeadingSlash() {
        for (String name : List.of("/config/env", "config/env")) {
            setJsonBody("{\"Name\":\"" + name + "\"}");
            assertEquals(List.of("arn:aws:ssm:us-east-1:000000000000:parameter/config/env"),
                    builder.buildResources("ssm", "ssm:GetParameter", ctx, "us-east-1", ACCOUNT, "aws"), name);
        }
    }

    @Test
    void ssmResourcePolicyActionsActOnTheParameterTheirResourceArnNames() {
        setJsonBody("{\"ResourceArn\":\"arn:aws:ssm:us-east-1:000000000000:parameter/prod/db\",\"Policy\":\"{}\"}");
        assertEquals(List.of("arn:aws:ssm:us-east-1:000000000000:parameter/prod/db"),
                builder.buildResources("ssm", "ssm:PutResourcePolicy", ctx, "us-east-1", ACCOUNT, "aws"));

        setJsonBody("{\"Name\":\"/prod/db\",\"ResourceArn\":\"arn:aws:ssm:us-east-1:000000000000:parameter/dev/db\"}");
        assertEquals(List.of("arn:aws:ssm:us-east-1:000000000000:parameter/prod/db"),
                builder.buildResources("ssm", "ssm:GetParameter", ctx, "us-east-1", ACCOUNT, "aws"));
    }

    // ── SQS ─────────────────────────────────────────────────────────────────────

    /** A builder reading what an in-memory SQS holds, its queues in {@code account}. */
    private static ResourceArnBuilder sqsHolding(String account, String... queues) {
        SqsService sqs = SqsServiceFactory.createInMemory("http://localhost:4566",
                new RegionResolver("us-east-1", account));
        for (String queue : queues) {
            sqs.createQueue(queue, Map.of(), "us-east-1");
        }
        return reading(new SqsStoredResourceArns(sqs));
    }

    /**
     * A Query request is checked on the queue its body's QueueUrl reaches, which SQS finds as its handlers do; the
     * handler acts on the body's QueueUrl, so a decoy in the query string is not checked.
     */
    @Test
    void sqsChecksTheQueueAFormBodysQueueUrlReaches() {
        ResourceArnBuilder builder = sqsHolding(ACCOUNT, "orders", "allowed");
        queryParams.add("QueueUrl", "http://localhost:4566/000000000000/allowed");
        setFormBody("Action=SendMessage&QueueUrl=http%3A%2F%2Flocalhost%3A4566%2F000000000000%2Forders&MessageBody=x");
        assertEquals(List.of("arn:aws:sqs:us-east-1:000000000000:orders"),
                builder.buildResources("sqs", "sqs:SendMessage", ctx, "us-east-1", ACCOUNT, "aws"));
    }

    @Test
    void sqsChecksTheQueueAJsonBodysQueueUrlReaches() {
        ResourceArnBuilder builder = sqsHolding(ACCOUNT, "my-queue");
        setJsonBody("{\"QueueUrl\":\"http://localhost:4566/000000000000/my-queue\"}");
        assertEquals(List.of("arn:aws:sqs:us-east-1:000000000000:my-queue"),
                builder.buildResources("sqs", "sqs:GetQueueAttributes", ctx, "us-east-1", ACCOUNT, "aws"));
    }

    /** A queue URL naming another account reaches the queue that account holds, as the handlers find it. */
    @Test
    void sqsChecksTheQueueOfTheAccountItsUrlNames() {
        ResourceArnBuilder builder = sqsHolding("111122223333", "orders");
        setJsonBody("{\"QueueUrl\":\"http://localhost:4566/111122223333/orders\"}");
        assertEquals(List.of("arn:aws:sqs:us-east-1:111122223333:orders"),
                builder.buildResources("sqs", "sqs:SendMessage", ctx, "us-east-1", ACCOUNT, "aws"));
    }

    /** A URL reaching no queue leaves the queue's name open. */
    @Test
    void sqsChecksAnOpenQueueForAUrlReachingNone() {
        ResourceArnBuilder builder = sqsHolding(ACCOUNT);
        setJsonBody("{\"QueueUrl\":\"http://localhost:4566/000000000000/gone\"}");
        assertEquals(List.of("arn:aws:sqs:us-east-1:000000000000:*"),
                builder.buildResources("sqs", "sqs:SendMessage", ctx, "us-east-1", ACCOUNT, "aws"));
    }

    /** ListDeadLetterSourceQueues is checked on the dead-letter queue its QueueUrl reaches. */
    @Test
    void sqsChecksTheDeadLetterQueueItsUrlReaches() {
        ResourceArnBuilder builder = sqsHolding(ACCOUNT, "orders-dlq");
        setJsonBody("{\"QueueUrl\":\"http://localhost:4566/000000000000/orders-dlq\"}");
        assertEquals(List.of("arn:aws:sqs:us-east-1:000000000000:orders-dlq"),
                builder.buildResources("sqs", "sqs:ListDeadLetterSourceQueues", ctx, "us-east-1", ACCOUNT, "aws"));
    }

    @Test
    void sqsChecksANewQueueOnItsName() {
        ResourceArnBuilder builder = sqsHolding(ACCOUNT);
        setJsonBody("{\"QueueName\":\"order-queue\"}");
        assertEquals(List.of("arn:aws:sqs:us-east-1:000000000000:order-queue"),
                builder.buildResources("sqs", "sqs:CreateQueue", ctx, "us-east-1", ACCOUNT, "aws"));
    }

    /**
     * StartMessageMoveTask is checked for its own action on each queue ARN it carries, a named destination included,
     * and on the dead-letter queue alone when it names none.
     */
    @Test
    void sqsChecksAMoveTaskOnTheQueueArnsItCarries() {
        ResourceArnBuilder builder = sqsHolding(ACCOUNT, "orders-dlq", "orders");
        String dlq = "arn:aws:sqs:us-east-1:000000000000:orders-dlq";
        String destination = "arn:aws:sqs:us-east-1:000000000000:orders";
        setJsonBody("{\"SourceArn\":\"" + dlq + "\",\"DestinationArn\":\"" + destination + "\"}");
        assertEquals(List.of(destination, dlq),
                builder.buildResources("sqs", "sqs:StartMessageMoveTask", ctx, "us-east-1", ACCOUNT, "aws"));
        setJsonBody("{\"SourceArn\":\"" + dlq + "\"}");
        assertEquals(List.of(dlq),
                builder.buildResources("sqs", "sqs:StartMessageMoveTask", ctx, "us-east-1", ACCOUNT, "aws"));
    }

    /**
     * CancelMessageMoveTask names its task only by its TaskHandle, which names no queue, so it is checked on every
     * resource, and a grant scoped to a queue, or to the account's queues, does not allow it.
     */
    @Test
    void sqsChecksACancelledMoveTaskOnEveryResource() {
        ResourceArnBuilder builder = sqsHolding(ACCOUNT, "orders-dlq");
        setJsonBody("{\"TaskHandle\":\"eyJ0YXNrSWQiOiJ0In0=\"}");
        assertEquals(List.of("*"),
                builder.buildResources("sqs", "sqs:CancelMessageMoveTask", ctx, "us-east-1", ACCOUNT, "aws"));
    }

    // ── SNS ─────────────────────────────────────────────────────────────────────

    /**
     * A Query request is checked on the topic its body's TopicArn names, read from the generated table; the handler
     * acts on the body's TopicArn, so a decoy in the query string is not checked.
     */
    @Test
    void snsChecksTheTopicAFormBodysTopicArnNames() {
        queryParams.add("TopicArn", "arn:aws:sns:us-east-1:000000000000:allowed");
        setFormBody("Action=Publish&TopicArn=arn%3Aaws%3Asns%3Aus-east-1%3A000000000000%3Aalerts&Message=hi");
        assertEquals(List.of("arn:aws:sns:us-east-1:000000000000:alerts"),
                builder.buildResources("sns", "sns:Publish", ctx, "us-east-1", ACCOUNT, "aws"));
    }

    @Test
    void snsChecksTheTopicAJsonBodysTopicArnNames() {
        String topicArn = "arn:aws:sns:us-east-1:000000000000:alerts";
        setJsonBody("{\"TopicArn\":\"" + topicArn + "\"}");
        assertEquals(List.of(topicArn), builder.buildResources("sns", "sns:GetTopicAttributes", ctx, "us-east-1",
                ACCOUNT, "aws"));
    }

    /** A new topic is checked on the ARN its name gives, and a tag call on the topic its ResourceArn names. */
    @Test
    void snsChecksANewTopicOnItsNameAndATagCallOnItsResourceArn() {
        setJsonBody("{\"Name\":\"alerts\"}");
        assertEquals(List.of("arn:aws:sns:us-east-1:000000000000:alerts"),
                builder.buildResources("sns", "sns:CreateTopic", ctx, "us-east-1", ACCOUNT, "aws"));
        setJsonBody("{\"ResourceArn\":\"arn:aws:sns:us-east-1:000000000000:alerts\",\"Tags\":[]}");
        assertEquals(List.of("arn:aws:sns:us-east-1:000000000000:alerts"),
                builder.buildResources("sns", "sns:TagResource", ctx, "us-east-1", ACCOUNT, "aws"));
    }

    // ── CloudWatch ──────────────────────────────────────────────────────────────

    /**
     * A CloudWatch request is checked on each alarm its AlarmNames list names, or the one its AlarmName names, as
     * {@code alarm:<name>} in the request's Region, the ARN CloudWatch mints for an alarm; one naming no alarm is
     * checked on {@code *}.
     */
    @Test
    void cloudWatchChecksTheAlarmsARequestNames() {
        setFormBody("Action=DeleteAlarms&Version=2010-08-01&AlarmNames.member.1=high-cpu&AlarmNames.member.2=low-disk");
        assertEquals(List.of("arn:aws:cloudwatch:us-west-2:000000000000:alarm:high-cpu",
                        "arn:aws:cloudwatch:us-west-2:000000000000:alarm:low-disk"),
                builder.buildResources("cloudwatch", "cloudwatch:DeleteAlarms", ctx, "us-west-2", ACCOUNT, "aws"));
        setJsonBody("{\"AlarmName\":\"high-cpu\",\"StateValue\":\"ALARM\",\"StateReason\":\"test\"}");
        when(ctx.getMediaType()).thenReturn(MediaType.valueOf("application/x-amz-json-1.0"));
        assertEquals(List.of("arn:aws:cloudwatch:us-west-2:000000000000:alarm:high-cpu"),
                builder.buildResources("cloudwatch", "cloudwatch:SetAlarmState", ctx, "us-west-2", ACCOUNT, "aws"));
        setFormBody("Action=DescribeAlarms&Version=2010-08-01&AlarmNamePrefix=high");
        assertEquals(List.of("*"),
                builder.buildResources("cloudwatch", "cloudwatch:DescribeAlarms", ctx, "us-west-2", ACCOUNT, "aws"));
    }

    /**
     * A dashboard's ARN has no Region, as the Service Reference gives it and CloudWatch mints it, and a metric stream
     * is checked on the one its Name names; publishing or reading metric data names no resource, so is checked on
     * {@code *}.
     */
    @Test
    void cloudWatchChecksADashboardWithoutItsRegionAndAMetricStreamByItsName() {
        setFormBody("Action=DeleteDashboards&Version=2010-08-01&DashboardNames.member.1=ops");
        assertEquals(List.of("arn:aws:cloudwatch::000000000000:dashboard/ops"),
                builder.buildResources("cloudwatch", "cloudwatch:DeleteDashboards", ctx, "us-west-2", ACCOUNT, "aws"));
        setJsonBody("{\"Name\":\"to-firehose\"}");
        when(ctx.getMediaType()).thenReturn(MediaType.valueOf("application/x-amz-json-1.0"));
        assertEquals(List.of("arn:aws:cloudwatch:us-west-2:000000000000:metric-stream/to-firehose"),
                builder.buildResources("cloudwatch", "cloudwatch:GetMetricStream", ctx, "us-west-2", ACCOUNT, "aws"));
        setFormBody("Action=PutMetricData&Version=2010-08-01&Namespace=App&MetricData.member.1.MetricName=Hits");
        assertEquals(List.of("*"),
                builder.buildResources("cloudwatch", "cloudwatch:PutMetricData", ctx, "us-west-2", ACCOUNT, "aws"));
    }

    // ── AWS Config ──────────────────────────────────────────────────────────────

    /**
     * A config rule, a configuration recorder and a conformance pack carry an id Config mints in their ARNs, which a
     * request naming them by name does not give, so each is checked on the ARN Config stores it under; one Config holds
     * none of is checked on its name with that id open, or on every rule for a rule.
     */
    @Test
    void configChecksARuleARecorderAndAConformancePackOnTheArnsConfigStoresThemUnder() {
        AwsConfigService config = new AwsConfigService(new RegionResolver("us-east-1", ACCOUNT), null);
        ConfigFixtures.Stored stored = ConfigFixtures.stored(config, "us-east-1");
        ResourceArnBuilder reading = reading(new ConfigStoredResourceArns(config));

        setJsonBody("{\"ConfigRuleName\":\"versioning\"}");
        assertEquals(List.of(stored.rule()), config(reading, "DeleteEvaluationResults"));
        setJsonBody("{\"ConfigurationRecorderName\":\"default\"}");
        assertEquals(List.of(stored.recorder()), config(reading, "StopConfigurationRecorder"));
        setJsonBody("{\"ConformancePackName\":\"pack\"}");
        assertEquals(List.of(stored.pack()), config(reading, "DeleteConformancePack"));

        setJsonBody("{\"ConfigRuleName\":\"missing\"}");
        assertEquals(List.of("arn:aws:config:us-east-1:000000000000:config-rule/*"),
                config(reading, "DeleteEvaluationResults"));
        setJsonBody("{\"ConformancePackName\":\"other\"}");
        assertEquals(List.of("arn:aws:config:us-east-1:000000000000:conformance-pack/other/*"),
                config(reading, "DeleteConformancePack"));

        // A recorder put names its recorder in its ConfigurationRecorder structure, the stored one when it exists.
        setJsonBody("{\"ConfigurationRecorder\":{\"name\":\"default\",\"roleARN\":\"arn:aws:iam::000000000000:role/r\"}}");
        assertEquals(List.of(stored.recorder()), config(reading, "PutConfigurationRecorder"));
        // An aggregation authorization is the caller's, its path the account and Region it authorizes.
        setJsonBody("{\"AuthorizedAccountId\":\"111122223333\",\"AuthorizedAwsRegion\":\"eu-west-1\"}");
        assertEquals(List.of("arn:aws:config:us-east-1:000000000000:aggregation-authorization/111122223333/eu-west-1"),
                config(reading, "PutAggregationAuthorization"));
    }

    /** The resources an AWS Config request is checked on, as the table reads {@code operation}. */
    private List<String> config(ResourceArnBuilder builder, String operation) {
        return builder.buildResources("config", "config:" + operation, ctx, "us-east-1", ACCOUNT, "aws");
    }

    // ── AWS DMS ─────────────────────────────────────────────────────────────────

    /**
     * The Service Reference gives each DMS type an ARN format ending in a literal {@code *}, which the reader matches
     * as written, so a request naming an endpoint by its ARN matches no format and is checked on {@code *}, and one
     * naming a subnet group by its identifier is checked on the format itself.
     */
    @Test
    void dmsChecksASubnetGroupOnTheFormatTheReferenceGivesIt() {
        setJsonBody("{\"EndpointArn\":\"arn:aws:dms:us-east-1:000000000000:endpoint:ABCDEFGHIJKLMNOP\"}");
        assertEquals(List.of("*"),
                builder.buildResources("dms", "dms:DeleteEndpoint", ctx, "us-east-1", ACCOUNT, "aws"));
        setJsonBody("{\"ReplicationSubnetGroupIdentifier\":\"default\"}");
        assertEquals(List.of("arn:aws:dms:us-east-1:000000000000:subgrp:*"),
                builder.buildResources("dms", "dms:DeleteReplicationSubnetGroup", ctx, "us-east-1", ACCOUNT, "aws"));
        setJsonBody("{}");
        assertEquals(List.of("*"),
                builder.buildResources("dms", "dms:DescribeEndpoints", ctx, "us-east-1", ACCOUNT, "aws"));
    }

    // ── ElastiCache ─────────────────────────────────────────────────────────────

    /**
     * An ElastiCache request is checked on the cluster, replication group or parameter group its identifier names, as
     * {@code <type>:<name>} in the request's Region, the ARN ElastiCache mints; a tag call names its resource in
     * {@code ResourceName}, which the table does not read, so it is checked on {@code *}.
     */
    @Test
    void elastiCacheChecksTheResourceItsIdentifierNames() {
        setFormBody("Action=DescribeCacheClusters&Version=2015-02-02&CacheClusterId=cache-1");
        assertEquals(List.of("arn:aws:elasticache:us-west-2:000000000000:cluster:cache-1"),
                builder.buildResources("elasticache", "elasticache:DescribeCacheClusters", ctx, "us-west-2", ACCOUNT,
                        "aws"));
        setFormBody("Action=CreateCacheParameterGroup&Version=2015-02-02&CacheParameterGroupName=tuned"
                + "&CacheParameterGroupFamily=redis7&Description=d");
        assertEquals(List.of("arn:aws:elasticache:us-west-2:000000000000:parametergroup:tuned"),
                builder.buildResources("elasticache", "elasticache:CreateCacheParameterGroup", ctx, "us-west-2",
                        ACCOUNT, "aws"));
        setFormBody("Action=ListTagsForResource&Version=2015-02-02"
                + "&ResourceName=arn%3Aaws%3Aelasticache%3Aus-west-2%3A000000000000%3Acluster%3Acache-1");
        assertEquals(List.of("*"), builder.buildResources("elasticache", "elasticache:ListTagsForResource", ctx,
                "us-west-2", ACCOUNT, "aws"));
        setFormBody("Action=DescribeCacheClusters&Version=2015-02-02");
        assertEquals(List.of("*"), builder.buildResources("elasticache", "elasticache:DescribeCacheClusters", ctx,
                "us-west-2", ACCOUNT, "aws"));
        // A user group's change is checked on the group and on each user it adds or removes.
        setFormBody("Action=ModifyUserGroup&Version=2015-02-02&UserGroupId=admins&UserIdsToAdd.member.1=alice"
                + "&UserIdsToRemove.member.1=bob");
        assertEquals(List.of("arn:aws:elasticache:us-west-2:000000000000:usergroup:admins",
                        "arn:aws:elasticache:us-west-2:000000000000:user:alice",
                        "arn:aws:elasticache:us-west-2:000000000000:user:bob"),
                builder.buildResources("elasticache", "elasticache:ModifyUserGroup", ctx, "us-west-2", ACCOUNT, "aws"));
    }

    // ── Identity Store ──────────────────────────────────────────────────────────

    /**
     * An Identity Store request is checked on the identity store its IdentityStoreId names and on the user or group its
     * id names, whose ARN has no Region and no account; a list is checked on every user, as the Service Reference gives
     * it.
     */
    @Test
    void identityStoreChecksTheStoreAndTheUserARequestNames() {
        setJsonBody("{\"IdentityStoreId\":\"d-1234567890\",\"UserId\":\"1234567890-u1\"}");
        assertEquals(List.of("arn:aws:identitystore::000000000000:identitystore/d-1234567890",
                        "arn:aws:identitystore:::user/1234567890-u1"),
                builder.buildResources("identitystore", "identitystore:DescribeUser", ctx, "us-east-1", ACCOUNT, "aws"));
        setJsonBody("{\"IdentityStoreId\":\"d-1234567890\"}");
        assertEquals(List.of("arn:aws:identitystore:::user/*",
                        "arn:aws:identitystore::000000000000:identitystore/d-1234567890"),
                builder.buildResources("identitystore", "identitystore:ListUsers", ctx, "us-east-1", ACCOUNT, "aws"));
    }

    /**
     * An identity store is checked in the account whose instance holds it, which the service answers for the store an
     * {@code IdentityStoreId} names, not in the caller's.
     */
    @Test
    void identityStoreChecksTheStoreInTheAccountItsServiceGives() {
        ResourceArnBuilder reading = reading(new StoredResourceArns() {
            @Override
            public String namespace() {
                return "identitystore";
            }

            @Override
            public Optional<String> find(String type, List<String> names, String region) {
                return "Identitystore".equals(type)
                        ? Optional.of("arn:aws:identitystore::111122223333:identitystore/" + names.getFirst())
                        : Optional.empty();
            }
        });
        setJsonBody("{\"IdentityStoreId\":\"d-1234567890\",\"UserId\":\"1234567890-u1\"}");
        assertEquals(List.of("arn:aws:identitystore::111122223333:identitystore/d-1234567890",
                        "arn:aws:identitystore:::user/1234567890-u1"),
                reading.buildResources("identitystore", "identitystore:DescribeUser", ctx, "us-east-1", ACCOUNT,
                        "aws"));
    }

    /**
     * A required type an Identity Store request does not name is asked of the service: a membership's group and user
     * through its id, and a lookup's user by the members of the one way its alternate identifier is given, with their
     * paths, as the table writes them.
     */
    @Test
    void identityStoreAsksTheServiceForTheRequiredTypesARequestDoesNotName() {
        List<List<String>> asked = new ArrayList<>();
        ResourceArnBuilder reading = reading(new StoredResourceArns() {
            @Override
            public String namespace() {
                return "identitystore";
            }

            @Override
            public Optional<String> find(String type, List<String> names, String region) {
                return Optional.empty();
            }

            @Override
            public Optional<String> findByMembers(String type, List<String> members, List<String> names,
                                                  String region) {
                asked.add(members);
                return Optional.of("arn:aws:identitystore:::user/" + names.getLast());
            }

            @Override
            public List<String> through(String member, String value, String region) {
                return List.of("arn:aws:identitystore:::group/g-of-" + value,
                        "arn:aws:identitystore:::user/u-of-" + value);
            }
        });
        setJsonBody("{\"IdentityStoreId\":\"d-1234567890\",\"MembershipId\":\"m1\"}");
        assertEquals(List.of("arn:aws:identitystore:::membership/m1",
                        "arn:aws:identitystore::000000000000:identitystore/d-1234567890",
                        "arn:aws:identitystore:::group/g-of-m1", "arn:aws:identitystore:::user/u-of-m1"),
                reading.buildResources("identitystore", "identitystore:DeleteGroupMembership", ctx, "us-east-1",
                        ACCOUNT, "aws"));
        setJsonBody("{\"IdentityStoreId\":\"d-1234567890\",\"AlternateIdentifier\":{\"UniqueAttribute\":"
                + "{\"AttributePath\":\"userName\",\"AttributeValue\":\"alice\"}}}");
        assertEquals(List.of("arn:aws:identitystore::000000000000:identitystore/d-1234567890",
                        "arn:aws:identitystore:::user/alice"),
                reading.buildResources("identitystore", "identitystore:GetUserId", ctx, "us-east-1", ACCOUNT, "aws"));
        assertEquals(List.of(List.of("IdentityStoreId", "AlternateIdentifier.UniqueAttribute.AttributePath",
                "AlternateIdentifier.UniqueAttribute.AttributeValue")), asked);
    }

    // ── MemoryDB ────────────────────────────────────────────────────────────────

    /**
     * A MemoryDB request is checked on the cluster, ACL or user its name names, as {@code <type>/<name>}, the ARN
     * MemoryDB mints. The Service Reference lists no {@code cluster} for CreateCluster but the multi-Region cluster it
     * may join, named by its MultiRegionClusterName, not by the new cluster's ClusterName, though both formats call
     * their variable {@code ${ClusterName}}; DeleteCluster is checked on the cluster its ClusterName names.
     */
    @Test
    void memoryDbChecksTheResourceItsNameNamesAsTheGeneratedTableReadsIt() {
        setJsonBody("{\"ClusterName\":\"cache\"}");
        assertEquals(List.of("arn:aws:memorydb:us-east-1:000000000000:cluster/cache"),
                builder.buildResources("memorydb", "memorydb:DescribeClusters", ctx, "us-east-1", ACCOUNT, "aws"));
        setJsonBody("{\"ClusterName\":\"cache\",\"NodeType\":\"db.t4g.small\",\"ACLName\":\"open-access\","
                + "\"MultiRegionClusterName\":\"global\"}");
        assertEquals(List.of("arn:aws:memorydb:us-east-1:000000000000:acl/open-access",
                        "arn:aws:memorydb::000000000000:multiregioncluster/global"),
                builder.buildResources("memorydb", "memorydb:CreateCluster", ctx, "us-east-1", ACCOUNT, "aws"));
        setJsonBody("{\"ClusterName\":\"cache\"}");
        assertEquals(List.of("arn:aws:memorydb:us-east-1:000000000000:cluster/cache"),
                builder.buildResources("memorydb", "memorydb:DeleteCluster", ctx, "us-east-1", ACCOUNT, "aws"));
    }

    // ── Route 53 Resolver ───────────────────────────────────────────────────────

    /**
     * A Route 53 Resolver request is checked on the rule, endpoint or domain list its id names, as {@code <type>/<id>},
     * the ARN Route 53 Resolver mints. A DNSSEC configuration's format takes a ResourceId, which the request gives as
     * the VPC's id, so the generated table checks the configuration under the VPC's id.
     */
    @Test
    void route53ResolverChecksTheResourceItsIdNames() {
        setJsonBody("{\"ResolverRuleId\":\"rslvr-rr-abc123\"}");
        assertEquals(List.of("arn:aws:route53resolver:us-east-1:000000000000:resolver-rule/rslvr-rr-abc123"),
                builder.buildResources("route53resolver", "route53resolver:GetResolverRule", ctx, "us-east-1",
                        ACCOUNT, "aws"));
        setJsonBody("{\"ResourceId\":\"vpc-0abc\"}");
        assertEquals(List.of("arn:aws:route53resolver:us-east-1:000000000000:resolver-dnssec-config/vpc-0abc"),
                builder.buildResources("route53resolver", "route53resolver:GetResolverDnssecConfig", ctx,
                        "us-east-1", ACCOUNT, "aws"));
        // An outpost resolver's create names the Outpost it runs on, another service's ARN, and makes its own.
        setJsonBody("{\"Name\":\"edge\",\"OutpostArn\":\"arn:aws:outposts:us-east-1:000000000000:outpost/op-1\"}");
        assertEquals(List.of("arn:aws:route53resolver:us-east-1:000000000000:outpost-resolver/*"),
                builder.buildResources("route53resolver", "route53resolver:CreateOutpostResolver", ctx, "us-east-1",
                        ACCOUNT, "aws"));
    }

    // ── SageMaker ───────────────────────────────────────────────────────────────

    /**
     * A SageMaker request is checked on the model, endpoint configuration, endpoint or training job its name names, as
     * {@code <type>/<name>}, the ARN Floci's SageMaker mints: CreateEndpoint on the endpoint it makes and the
     * configuration it runs, each named after its type.
     */
    @Test
    void sageMakerChecksTheResourceItsNameNames() {
        setJsonBody("{\"ModelName\":\"churn\"}");
        assertEquals(List.of("arn:aws:sagemaker:us-east-1:000000000000:model/churn"),
                builder.buildResources("sagemaker", "sagemaker:DescribeModel", ctx, "us-east-1", ACCOUNT, "aws"));
        setJsonBody("{\"EndpointName\":\"churn\",\"EndpointConfigName\":\"churn-config\"}");
        assertEquals(List.of("arn:aws:sagemaker:us-east-1:000000000000:endpoint/churn",
                        "arn:aws:sagemaker:us-east-1:000000000000:endpoint-config/churn-config"),
                builder.buildResources("sagemaker", "sagemaker:CreateEndpoint", ctx, "us-east-1", ACCOUNT, "aws"));
    }

    // ── Transcribe ──────────────────────────────────────────────────────────────

    /**
     * A Transcribe request is checked on the transcription job or vocabulary its name names, as {@code <type>/<name>};
     * the Service Reference lists no resource type for starting a job or creating a vocabulary, so those are checked on
     * {@code *}.
     */
    @Test
    void transcribeChecksTheJobOrVocabularyItsNameNames() {
        setJsonBody("{\"TranscriptionJobName\":\"call-1\"}");
        assertEquals(List.of("arn:aws:transcribe:us-east-1:000000000000:transcription-job/call-1"),
                builder.buildResources("transcribe", "transcribe:GetTranscriptionJob", ctx, "us-east-1", ACCOUNT,
                        "aws"));
        setJsonBody("{\"TranscriptionJobName\":\"call-2\",\"Media\":{\"MediaFileUri\":\"s3://b/k.mp4\"}}");
        assertEquals(List.of("*"), builder.buildResources("transcribe", "transcribe:StartTranscriptionJob", ctx,
                "us-east-1", ACCOUNT, "aws"));
    }

    // ── S3 and S3 Control ───────────────────────────────────────────────────────

    /** The resources an S3 or S3 Control request names, read from the generated table on the route it matches. */
    private List<String> s3(ResourceArnBuilder from, String method, String sent, String operation, String region,
                            String partition) {
        when(ctx.getMethod()).thenReturn(method);
        request(sent);
        return from.buildResources("s3", "s3:" + operation, ctx, region, ACCOUNT, partition);
    }

    private List<String> s3(String method, String sent, String operation) {
        return s3(builder, method, sent, operation, "us-east-1", "aws");
    }

    /** A builder reading S3's keys as S3 does, from {@code received}, the path the server received. */
    private static ResourceArnBuilder readingS3Keys(String received) {
        RequestPathLabels s3Keys = new S3RequestPathLabels(vertxRequestOn(received));
        @SuppressWarnings("unchecked")
        Instance<RequestPathLabels> labels = mock(Instance.class);
        when(labels.isUnsatisfied()).thenReturn(false);
        when(labels.iterator()).thenAnswer(invocation -> List.of(s3Keys).iterator());
        return new ResourceArnBuilder(new ObjectMapper(), null, labels);
    }

    /** A current request whose path is {@code sentPath}, the path the server received. */
    private static CurrentVertxRequest vertxRequestOn(String sentPath) {
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(request.path()).thenReturn(sentPath);
        RoutingContext routingContext = mock(RoutingContext.class);
        when(routingContext.request()).thenReturn(request);
        CurrentVertxRequest current = mock(CurrentVertxRequest.class);
        when(current.getCurrent()).thenReturn(routingContext);
        return current;
    }

    /** A bucket and an object are named by their route's labels, in the partition of the request's Region. */
    @Test
    void anS3RequestIsCheckedOnTheBucketOrObjectItsRouteNames() {
        assertEquals(List.of("arn:aws:s3:::my-bucket"), s3("PUT", "/my-bucket", "CreateBucket"));
        assertEquals(List.of("arn:aws:s3:::my-bucket"), s3("GET", "/my-bucket?list-type=2", "ListObjectsV2"));
        assertEquals(List.of("arn:aws:s3:::my-bucket/folder/file.json"),
                s3("GET", "/my-bucket/folder/file.json", "GetObject"));
        assertEquals(List.of("arn:aws-cn:s3:::my-bucket/key.txt"),
                s3(builder, "GET", "/my-bucket/key.txt", "GetObject", "cn-north-1", "aws-cn"));
        // ListBuckets acts on no resource type.
        assertEquals(List.of("*"), s3("GET", "/", "ListBuckets"));
    }

    /**
     * A key keeps the slashes it is sent with, and so does the ARN the request is checked against, read as S3 reads
     * it: {@code PUT /bucket//email.txt} stores the key {@code /email.txt}; read from the router's collapsed path it
     * would be checked on {@code bucket/email.txt}, so a grant on that object would cover this one.
     */
    @Test
    void anS3KeyKeepsTheSlashesItWasSentWith() {
        assertEquals(List.of("arn:aws:s3:::my-bucket//email.txt"),
                s3(readingS3Keys("/my-bucket//email.txt"), "PUT", "/my-bucket//email.txt", "PutObject", "us-east-1",
                        "aws"));
        assertEquals(List.of("arn:aws:s3:::my-bucket/a//b.txt"),
                s3(readingS3Keys("/my-bucket/a//b.txt"), "GET", "/my-bucket/a//b.txt", "GetObject", "us-east-1",
                        "aws"));
        assertEquals(List.of("arn:aws:s3:::my-bucket/folder/"),
                s3(readingS3Keys("/my-bucket/folder/"), "PUT", "/my-bucket/folder/", "PutObject", "us-east-1", "aws"));
        // Virtual-hosted: S3VirtualHostFilter put the bucket into the path, and the server received the key alone.
        assertEquals(List.of("arn:aws:s3:::my-bucket//email.txt"),
                s3(readingS3Keys("//email.txt"), "GET", "/my-bucket//email.txt", "GetObject", "us-east-1", "aws"));
    }

    /**
     * An S3 Control tagging route names the resource it acts on by its ARN, and that ARN as written, percent-encoded as
     * the Go SDK sends it or not, is what the request is checked against, never a bucket named v20180820 (issue
     * #6537). One naming no ARN stays on the wildcard, under which the handler refuses it.
     */
    @Test
    void anS3ControlTaggingRouteIsCheckedOnTheArnItNames() {
        for (String[] call : List.of(new String[] {"GET", "ListTagsForResource"}, new String[] {"POST", "TagResource"},
                new String[] {"DELETE", "UntagResource"})) {
            assertEquals(List.of("arn:aws:s3:::repro-bucket"),
                    s3(call[0], "/v20180820/tags/arn:aws:s3:::repro-bucket", call[1]), call[1]);
            assertEquals(List.of("arn:aws:s3:::repro-bucket"),
                    s3(call[0], "/v20180820/tags/arn%3Aaws%3As3%3A%3A%3Arepro-bucket", call[1]), call[1]);
        }
        assertEquals(List.of("*"), s3("GET", "/v20180820/tags/not-an-arn", "ListTagsForResource"));
    }

    /**
     * AWS gives s3:ListAccessPoints and the account-level Block Public Access actions no resource type, so a request
     * of them is checked on the wildcard alone, as other namespaces' no-resource actions are.
     */
    @Test
    void anS3ControlAccountRouteIsCheckedOnTheWildcardAlone() {
        assertEquals(List.of("*"), s3("GET", "/v20180820/accesspoint", "ListAccessPoints"));
        assertEquals(List.of("*"), s3("GET", "/v20180820/configuration/publicAccessBlock", "GetPublicAccessBlock"));
        assertEquals(List.of("*"), s3("PUT", "/v20180820/configuration/publicAccessBlock", "PutPublicAccessBlock"));
    }

    /** A data-plane request into a bucket named v20180820 keeps its own bucket and object. */
    @Test
    void aDataPlaneRequestUnderTheS3ControlPrefixKeepsItsOwnBucketAndObject() {
        assertEquals(List.of("arn:aws:s3:::v20180820/tags/key.txt"), s3("PUT", "/v20180820/tags/key.txt", "PutObject"));
        assertEquals(List.of("arn:aws:s3:::v20180820"), s3("GET", "/v20180820", "ListObjects"));
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

    private static final String FUNCTION = "arn:aws:lambda:us-east-1:000000000000:function:my-function";
    private static final String OTHER_FUNCTION = "arn:aws:lambda:us-east-1:111122223333:function:my-function";

    /** The resources a Lambda request names, read from the generated table for the operation it is dispatched as. */
    private List<String> lambda(String method, String sent, String operation) {
        return lambda(builder, method, sent, operation);
    }

    private List<String> lambda(ResourceArnBuilder from, String method, String sent, String operation) {
        when(ctx.getMethod()).thenReturn(method);
        request(sent);
        return from.buildResources("lambda", "lambda:" + operation, ctx, "us-east-1", ACCOUNT, "aws");
    }

    /** A function is named by its name, its ARN or a partial ARN, and a whole or partial ARN keeps its account. */
    @Test
    void aFunctionIsNamedByItsNameOrItsWholeOrPartialArn() {
        assertEquals(List.of(FUNCTION), lambda("POST", "/2015-03-31/functions/my-function/invocations", "Invoke"));
        assertEquals(Set.of(OTHER_FUNCTION, FUNCTION), Set.copyOf(lambda("POST",
                "/2015-03-31/functions/" + OTHER_FUNCTION + "/invocations", "Invoke")));
        assertEquals(Set.of(OTHER_FUNCTION, FUNCTION), Set.copyOf(lambda("POST",
                "/2015-03-31/functions/111122223333:function:my-function/invocations", "Invoke")));
        assertEquals(Set.of("arn:aws:lambda:eu-west-1:111122223333:function:my-function",
                        "arn:aws:lambda:eu-west-1:000000000000:function:my-function",
                        "arn:aws:lambda:us-east-1:111122223333:function:my-function", FUNCTION),
                Set.copyOf(lambda("GET", "/2015-03-31/functions/eu-west-1:111122223333:function:my-function",
                        "GetFunction")));
        // Code-signing attachment names its function by Lambda's broader shape, the resource part alone included.
        assertEquals(List.of("arn:aws:lambda:us-east-1:000000000000:function:protected"),
                lambda("GET", "/2020-06-30/functions/function:protected/code-signing-config",
                        "GetFunctionCodeSigningConfig"));
        // A reference Lambda refuses is named as given: its handler answers it with a 400.
        assertEquals(List.of("arn:aws:lambda:us-east-1:000000000000:function:not:a:function:ref"),
                lambda("POST", "/2015-03-31/functions/not:a:function:ref/invocations", "Invoke"));
    }

    /**
     * A version or alias written into the function's name is part of the name it is checked on; the Qualifier member
     * names nothing for an action the Service Reference gives the function alone.
     */
    @Test
    void aQualifierInTheNameIsPartOfTheFunctionItNames() {
        assertEquals(List.of(FUNCTION + ":1"), lambda("DELETE", "/2015-03-31/functions/my-function:1",
                "DeleteFunction"));
        assertEquals(Set.of(OTHER_FUNCTION + ":live", FUNCTION + ":live"), Set.copyOf(lambda("POST",
                "/2015-03-31/functions/" + OTHER_FUNCTION + ":live/invocations", "Invoke")));
        queryParams.putSingle("Qualifier", "1");
        assertEquals(List.of(FUNCTION), lambda("POST", "/2015-03-31/functions/my-function/invocations", "Invoke"));
        assertEquals(List.of(FUNCTION), lambda("GET", "/2015-03-31/functions/my-function", "GetFunction"));
        assertEquals(List.of(FUNCTION), lambda("POST",
                "/2021-11-15/functions/my-function/response-streaming-invocations", "InvokeWithResponseStream"));
    }

    /** Provisioned concurrency acts on the alias or version its Qualifier names, of the function it names. */
    @Test
    void provisionedConcurrencyActsOnTheVersionOrAliasItsQualifierNames() {
        queryParams.putSingle("Qualifier", "live");
        assertEquals(List.of(FUNCTION + ":live"), lambda("GET",
                "/2019-09-30/functions/my-function/provisioned-concurrency", "GetProvisionedConcurrencyConfig"));
        assertEquals(Set.of(OTHER_FUNCTION + ":live", FUNCTION + ":live"), Set.copyOf(lambda("GET",
                "/2019-09-30/functions/" + OTHER_FUNCTION + "/provisioned-concurrency",
                "GetProvisionedConcurrencyConfig")));
    }

    /** CreateFunction is checked on the function its body names. */
    @Test
    void createFunctionIsCheckedOnTheFunctionItsBodyNames() {
        setJsonBody("{\"FunctionName\":\"my-function\",\"Runtime\":\"nodejs20.x\"}");
        assertEquals(List.of(FUNCTION), lambda("POST", "/2015-03-31/functions", "CreateFunction"));
        setJsonBody("{\"FunctionName\":\"" + OTHER_FUNCTION + "\"}");
        assertEquals(Set.of(OTHER_FUNCTION, FUNCTION), Set.copyOf(lambda("POST", "/2015-03-31/functions",
                "CreateFunction")));
        setJsonBody("{\"Runtime\":\"nodejs20.x\"}");
        assertEquals(List.of("*"), lambda("POST", "/2015-03-31/functions", "CreateFunction"));
    }

    @Test
    void webFunctionRoutesNameTheFunctionRevisionOrEndpointTheyActOn() {
        String fn = "arn:aws:lambda:us-east-1:000000000000:web-function/app";
        assertEquals(List.of(fn), lambda("GET", "/2025-03-07/web-functions/app", "GetWebFunction"));
        assertEquals(List.of(fn), lambda("POST", "/2025-03-07/web-functions/app/list-revisions",
                "ListWebFunctionRevisions"));
        assertEquals(List.of(fn), lambda("POST", "/2025-03-07/web-functions/app/list-endpoints",
                "ListWebFunctionEndpoints"));
        assertEquals(List.of(fn + "/revision/rev-abcde"), lambda("GET",
                "/2025-03-07/web-functions/app/revisions/rev-abcde", "GetWebFunctionRevision"));
        assertEquals(List.of(fn + "/endpoint/prod"), lambda("GET", "/2025-03-07/web-functions/app/endpoints/prod",
                "GetWebFunctionEndpoint"));
        // The tag and policy routes carry the function's ARN.
        assertEquals(List.of(fn), lambda("GET", "/2025-03-07/tags/" + fn.replace("/", "%2F"), "ListTags"));
        assertEquals(List.of(fn), lambda("GET", "/2025-03-07/resource-policy/" + fn.replace("/", "%2F"),
                "GetResourcePolicy"));
        // Listing functions and the account settings name no resource.
        assertEquals(List.of("*"), lambda("POST", "/2025-03-07/web-functions", "ListWebFunctions"));
        assertEquals(List.of("*"), lambda("GET", "/2025-03-07/web-account-settings", "GetWebAccountSettings"));
    }

    @Test
    void microvmImageAndNetworkConnectorRoutesNameTheImageOrConnectorTheyActOn() {
        String image = "arn:aws:lambda:us-east-1:000000000000:microvm-image:img";
        assertEquals(List.of(image), lambda("GET", "/2025-09-09/microvm-images/img", "GetMicrovmImage"));
        assertEquals(List.of(image), lambda("GET", "/2025-09-09/microvm-images/img/versions/1/builds/b-1",
                "GetMicrovmImageBuild"));
        // An image named by its ARN keeps the account and Region it names, beside the caller's of that name.
        String shared = "arn:aws:lambda:eu-west-1:111122223333:microvm-image:shared";
        assertEquals(Set.of(shared, "arn:aws:lambda:eu-west-1:000000000000:microvm-image:shared",
                        "arn:aws:lambda:us-east-1:111122223333:microvm-image:shared",
                        "arn:aws:lambda:us-east-1:000000000000:microvm-image:shared"),
                Set.copyOf(lambda("GET", "/2025-09-09/microvm-images/" + shared + "/versions",
                        "ListMicrovmImageVersions")));
        assertEquals(List.of("arn:aws:lambda:us-east-1:000000000000:network-connector:nc-1"),
                lambda("GET", "/2026-04-04/network-connectors/nc-1/versions", "ListNetworkConnectorVersions"));
        // RunMicrovm runs the image its imageIdentifier names.
        setJsonBody("{\"imageIdentifier\":\"img\"}");
        assertEquals(List.of(image), lambda("POST", "/2025-09-09/microvms", "RunMicrovm"));
        setJsonBody("{}");
        assertEquals(List.of("*"), lambda("POST", "/2025-09-09/microvms", "RunMicrovm"));
        // Listing images, connectors and MicroVMs names no resource.
        assertEquals(List.of("*"), lambda("GET", "/2025-09-09/microvm-images", "ListMicrovmImages"));
        assertEquals(List.of("*"), lambda("GET", "/2025-09-09/microvms", "ListMicrovms"));
        assertEquals(List.of("*"), lambda("GET", "/2026-04-04/network-connectors", "ListNetworkConnectors"));
    }

    /** An action on a running MicroVM is checked on the image it runs, which Lambda gives for the caller's MicroVM. */
    @Test
    void anActionOnARunningMicrovmIsCheckedAgainstTheImageItRuns() {
        String image = "arn:aws:lambda:us-east-1:000000000000:microvm-image:img";
        LambdaMicrovmsService.Microvm vm = new LambdaMicrovmsService.Microvm();
        vm.microvmId = "microvm-1";
        vm.imageArn = image;
        LambdaMicrovmsService microvms = mock(LambdaMicrovmsService.class);
        when(microvms.findMicrovm(any(), any())).thenReturn(Optional.empty());
        when(microvms.findMicrovm("us-east-1", "microvm-1")).thenReturn(Optional.of(vm));
        @SuppressWarnings("unchecked")
        Instance<LambdaMicrovmsService> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(microvms);
        ResourceArnBuilder backed = reading(new LambdaStoredResourceArns(null, null, instance, null));

        assertEquals(List.of(image), lambda(backed, "GET", "/2025-09-09/microvms/microvm-1", "GetMicrovm"));
        assertEquals(List.of(image), lambda(backed, "POST", "/2025-09-09/microvms/microvm-1/suspend",
                "SuspendMicrovm"));
        assertEquals(List.of(image), lambda(backed, "POST", "/2025-09-09/microvms/microvm-1/auth-token",
                "CreateMicrovmAuthToken"));
        assertEquals(List.of("*"), lambda(backed, "GET", "/2025-09-09/microvms/microvm-2", "GetMicrovm"));
    }

    @Test
    void mappingConfigAndLayerRoutesNameTheResourceTheyActOn() {
        assertEquals(List.of("arn:aws:lambda:us-east-1:000000000000:event-source-mapping:"
                        + "14e0db71-5d35-4eb5-b481-8945cf9d10c2"),
                lambda("GET", "/2015-03-31/event-source-mappings/14e0db71-5d35-4eb5-b481-8945cf9d10c2",
                        "GetEventSourceMapping"));
        String config = "arn:aws:lambda:us-east-1:000000000000:code-signing-config:csc-0123456789abcdef0";
        assertEquals(List.of(config), lambda("GET", "/2020-04-22/code-signing-configs/" + config,
                "GetCodeSigningConfig"));
        assertEquals(List.of(config), lambda("GET", "/2020-04-22/code-signing-configs/" + config + "/functions",
                "ListFunctionsByCodeSigningConfig"));
        // A layer version, its policy and its permissions.
        String version = "arn:aws:lambda:us-east-1:000000000000:layer:my-layer:3";
        assertEquals(version, layerResource("GetLayerVersion", "us-east-1", "GET",
                "/2018-10-31/layers/my-layer/versions/3"));
        assertEquals(version, layerResource("GetLayerVersionPolicy", "us-east-1", "GET",
                "/2018-10-31/layers/my-layer/versions/3/policy"));
        assertEquals(version, layerResource("RemoveLayerVersionPermission", "us-east-1", "DELETE",
                "/2018-10-31/layers/my-layer/versions/3/policy/statement-1"));
        // GetLayerVersion accepts an unversioned ARN and checks the version that it names.
        assertEquals(version, layerResource("GetLayerVersion", "us-east-1", "GET",
                "/2018-10-31/layers/arn:aws:lambda:us-east-1:000000000000:layer:my-layer/versions/3"));
        // GetLayerVersionByArn reads the version its Arn names.
        queryParams.putSingle("find", "LayerVersion");
        queryParams.putSingle("Arn", version);
        assertEquals(version, layerResource("GetLayerVersionByArn", "us-east-1", "GET", "/2018-10-31/layers"));
        queryParams.clear();
        // Listing layers, a layer's versions or configs, and creating a config, name no resource.
        assertEquals("*", layerResource("ListLayers", "us-east-1", "GET", "/2018-10-31/layers"));
        assertEquals("*", layerResource("ListLayerVersions", "us-east-1", "GET", "/2018-10-31/layers/my-layer/versions"));
        assertEquals(List.of("*"), lambda("GET", "/2020-04-22/code-signing-configs", "ListCodeSigningConfigs"));
        assertEquals(List.of("*"), lambda("POST", "/2020-04-22/code-signing-configs", "CreateCodeSigningConfig"));
        // PublishLayerVersion makes a version of the layer, so it is checked against the layer.
        assertEquals("arn:aws:lambda:us-east-1:000000000000:layer:my-layer",
                layerResource("PublishLayerVersion", "us-east-1", "POST", "/2018-10-31/layers/my-layer/versions"));
    }

    @ParameterizedTest
    @CsvSource({"aws,us-west-2,us-east-1", "aws-cn,cn-northwest-1,cn-north-1",
            "aws-us-gov,us-gov-east-1,us-gov-west-1"})
    void lambdaLayerArnLookupKeepsItsCoordinatesAndNormalizesTheVersion(
            String partition, String layerRegion, String requestRegion) {
        String layer = "arn:" + partition + ":lambda:" + layerRegion + ":111122223333:layer:shared";
        String sent = "/2018-10-31/layers/" + layer.replace(":", "%3A") + "/versions/01";

        assertEquals(layer + ":1", layerResource("GetLayerVersion", requestRegion, "GET", sent));
        assertEquals(layer + ":1", layerResource("DeleteLayerVersion", requestRegion, "DELETE", sent));
    }

    @Test
    void aLayerVersionIsReadAsLambdaReadsItsNameAndNumber() {
        // Lambda parses the version number, so 01 is version 1, and the router keeps an encoded / inside the
        // layer's name, so the layer checked is the one Lambda looks up.
        assertEquals("arn:aws:lambda:us-east-1:000000000000:layer:my-layer:1",
                layerResource("GetLayerVersion", "us-east-1", "GET", "/2018-10-31/layers/my-layer/versions/01"));
        assertEquals("arn:aws:lambda:us-east-1:000000000000:layer:my/layer:3",
                layerResource("GetLayerVersionPolicy", "us-east-1", "GET",
                        "/2018-10-31/layers/my%2Flayer/versions/3/policy"));
        assertEquals("arn:aws:lambda:us-east-1:000000000000:layer:my/layer",
                layerResource("PublishLayerVersion", "us-east-1", "POST", "/2018-10-31/layers/my%2Flayer/versions"));
        // GetLayerVersionByArn parses the number the same way.
        queryParams.putSingle("find", "LayerVersion");
        queryParams.putSingle("Arn", "arn:aws:lambda:us-east-1:000000000000:layer:my-layer:01");
        assertEquals("arn:aws:lambda:us-east-1:000000000000:layer:my-layer:1",
                layerResource("GetLayerVersionByArn", "us-east-1", "GET", "/2018-10-31/layers"));
    }

    private String layerResource(String operation, String region, String method, String sent) {
        when(ctx.getMethod()).thenReturn(method);
        request(sent);
        return builder.buildResources("lambda", "lambda:" + operation, ctx, region, ACCOUNT,
                AwsRegions.partitionFor(region)).getFirst();
    }

    /**
     * An Aurora DSQL route is checked on the cluster or stream its path names, or the ARN its tagging route names,
     * from the table; CreateCluster and ListClusters on every cluster, as DSQL checks them (measured).
     */
    @Test
    void dsqlRoutesResolveTheClusterTheStreamOrTheTaggedArn() {
        String cluster = "arn:aws:dsql:eu-west-1:000000000000:cluster/abcdefghijklmnopqrstuvwxyz";
        when(ctx.getMethod()).thenReturn("GET");
        request("/cluster/abcdefghijklmnopqrstuvwxyz");
        assertEquals(List.of(cluster), dsql("dsql:GetCluster", "eu-west-1", "aws"));
        request("/cluster/abcdefghijklmnopqrstuvwxyz/policy");
        assertEquals(List.of(cluster), dsql("dsql:GetClusterPolicy", "eu-west-1", "aws"));
        request("/clusters/abcdefghijklmnopqrstuvwxyz/vpc-endpoint-service-name");
        assertEquals(List.of(cluster), dsql("dsql:GetVpcEndpointServiceName", "eu-west-1", "aws"));
        request("/tags/" + cluster.replace(":", "%3A").replace("/", "%2F"));
        assertEquals(List.of(cluster), dsql("dsql:ListTagsForResource", "eu-west-1", "aws"));
        // A stream route names the stream, or its cluster to create or list streams.
        request("/stream/abcdefghijklmnopqrstuvwxyz");
        assertEquals(List.of(cluster), dsql("dsql:ListStreams", "eu-west-1", "aws"));
        request("/stream/abcdefghijklmnopqrstuvwxyz/s1");
        assertEquals(List.of(cluster + "/stream/s1"), dsql("dsql:GetStream", "eu-west-1", "aws"));
        request("/cluster");
        assertEquals(List.of("arn:aws:dsql:eu-west-1:000000000000:cluster/*"),
                dsql("dsql:ListClusters", "eu-west-1", "aws"));
        when(ctx.getMethod()).thenReturn("POST");
        assertEquals(List.of("arn:aws:dsql:eu-west-1:000000000000:cluster/*"),
                dsql("dsql:CreateCluster", "eu-west-1", "aws"));
        when(ctx.getMethod()).thenReturn("GET");
        request("/cluster/abcdefghijklmnopqrstuvwxyz");
        assertEquals(List.of("arn:aws-cn:dsql:polygondwanaland-west-1:000000000000:cluster/abcdefghijklmnopqrstuvwxyz"),
                dsql("dsql:GetCluster", "polygondwanaland-west-1", "aws-cn"));
    }

    private List<String> dsql(String operation, String region, String partition) {
        return builder.buildResources("dsql", operation, ctx, region, "000000000000", partition);
    }

    /**
     * Bedrock's requests are read from the generated table on the route they match: a resource-policy route acts on
     * the resource whose ARN it carries, in its path or in its body's {@code resourceArn} for PutResourcePolicy, and a
     * guardrail route on the guardrail its path names.
     */
    @Test
    void aBedrockRequestIsCheckedOnTheResourceItsRouteNames() {
        String guardrail = "arn:aws:bedrock:us-east-1:000000000000:guardrail/abc123def456";
        // An SDK escapes a label's slashes, so the ARN is one segment of the path.
        String encoded = "arn%3Aaws%3Abedrock%3Aus-east-1%3A000000000000%3Aguardrail%2Fabc123def456";
        for (String[] call : List.of(new String[] {"GET", "GetResourcePolicy"},
                new String[] {"DELETE", "DeleteResourcePolicy"})) {
            when(ctx.getMethod()).thenReturn(call[0]);
            request("/resource-policy/" + encoded);
            assertEquals(List.of(guardrail), bedrock(call[1]), call[1]);
        }
        when(ctx.getMethod()).thenReturn("POST");
        request("/resource-policy");
        setJsonBody("{\"resourceArn\":\"" + guardrail + "\",\"resourcePolicy\":\"{}\"}");
        assertEquals(List.of(guardrail), bedrock("PutResourcePolicy"));
        // A value that is not an ARN names no resource.
        setJsonBody("{\"resourceArn\":\"abc123def456\",\"resourcePolicy\":\"{}\"}");
        assertEquals(List.of("*"), bedrock("PutResourcePolicy"));
        when(ctx.getMethod()).thenReturn("GET");
        request("/resource-policy/abc123def456");
        assertEquals(List.of("*"), bedrock("GetResourcePolicy"));
        request("/guardrails/abc123def456");
        assertEquals(List.of(guardrail), bedrock("GetGuardrail"));
    }

    private List<String> bedrock(String operation) {
        return builder.buildResources("bedrock", "bedrock:" + operation, ctx, "us-east-1", ACCOUNT, "aws");
    }

    /**
     * API Gateway's requests are read from the generated table on the route they match: a route authorized as its HTTP
     * method acts on the resource its own path names, with no account, the {@code /v2} of an HTTP or WebSocket API's
     * left out. The router resolves dot segments and empty segments before the route is matched, which
     * {@code ApiGatewayPathArnEnforcementIntegrationTest} covers through the server.
     */
    @Test
    void anApiGatewayRouteIsCheckedOnThePathItNames() {
        when(ctx.getMethod()).thenReturn("GET");
        request("/restapis/abc123/stages");
        assertEquals(List.of("arn:aws:apigateway:us-east-1::/restapis/abc123/stages"), apiGateway("GetStages"));
        // A label is decoded as the router decodes it, so an encoded id names the API it routes to.
        request("/restapis/%61bc123/stages");
        assertEquals(List.of("arn:aws:apigateway:us-east-1::/restapis/abc123/stages"), apiGateway("GetStages"));
        // The guide's REST API row, where the Service Reference gives the HTTP API's stage.
        request("/restapis/abc123/stages/prod");
        assertEquals(List.of("arn:aws:apigateway:us-east-1::/restapis/abc123/stages/prod"), apiGateway("GetStage"));
        request("/restapis/");
        assertEquals(List.of("arn:aws:apigateway:us-east-1::/restapis"), apiGateway("GetRestApis"));
        // A label's own slash or escape stays in its segment, so it cannot pass for a path of more segments.
        request("/restapis/abc%2Fstages%2Fprod");
        assertEquals(List.of("arn:aws:apigateway:us-east-1::/restapis/abc%2Fstages%2Fprod"), apiGateway("GetRestApi"));
        request("/restapis/abc%2525");
        assertEquals(List.of("arn:aws:apigateway:us-east-1::/restapis/abc%2525"), apiGateway("GetRestApi"));
        // A path the guide publishes no row for is its own resource path.
        request("/sdktypes");
        assertEquals(List.of("arn:aws:apigateway:us-east-1::/sdktypes"), apiGateway("GetSdkTypes"));
        // An HTTP or WebSocket API path's /v2 is not part of the resource it names.
        request("/v2/apis");
        assertEquals(List.of("arn:aws:apigateway:us-east-1::/apis"), apiGateway("GetApis"));
        request("/v2/apis/a1b2c3/stages/prod");
        assertEquals(List.of("arn:aws:apigateway:us-east-1::/apis/a1b2c3/stages/prod"), apiGateway("GetStage"));
        // A stage name keeps the characters it is written with, sent literally or encoded.
        request("/v2/apis/a1b2c3/stages/$default");
        assertEquals(List.of("arn:aws:apigateway:us-east-1::/apis/a1b2c3/stages/$default"), apiGateway("GetStage"));
        request("/v2/apis/a1b2c3/stages/%24default");
        assertEquals(List.of("arn:aws:apigateway:us-east-1::/apis/a1b2c3/stages/$default"), apiGateway("GetStage"));
        // A tagging route names the ARN it tags URL-encoded, as AWS names its Tags resource, however it is sent.
        String tags = "arn:aws:apigateway:us-east-1::/tags/arn%3Aaws%3Aapigateway%3Aus-east-1%3A%3A%2Fapis%2Fa1b2c3";
        request("/v2/tags/arn%3Aaws%3Aapigateway%3Aus-east-1%3A%3A%2Fapis%2Fa1b2c3");
        assertEquals(List.of(tags), apiGateway("GetTags"));
        request("/v2/tags/arn:aws:apigateway:us-east-1::/apis/a1b2c3");
        assertEquals(List.of(tags), apiGateway("GetTags"));
        request("/tags/arn%3aaws%3aapigateway%3aus-east-1%3a%3a%2frestapis%2fabc123");
        assertEquals(List.of("arn:aws:apigateway:us-east-1::/tags/"
                + "arn%3Aaws%3Aapigateway%3Aus-east-1%3A%3A%2Frestapis%2Fabc123"), apiGateway("GetTags"));
        // The guide's domain name access associations carry the account.
        request("/domainnameaccessassociations");
        assertEquals(List.of("arn:aws:apigateway:us-east-1:" + ACCOUNT + ":/domainnameaccessassociations"),
                apiGateway("GetDomainNameAccessAssociations"));
        request("/restapis");
        assertEquals(List.of("arn:aws-us-gov:apigateway:us-gov-west-1::/restapis"),
                builder.buildResources("apigateway", "apigateway:GetRestApis", ctx, "us-gov-west-1", ACCOUNT,
                        "aws-us-gov"));
    }

    /** API Gateway's operations AWS authorizes as their method by several of its actions, and their own named ones. */
    @Test
    void anApiGatewayRouteOfSeveralActionsOrAnArnItCarriesIsCheckedAsTheTableReadsIt() {
        // CreateRestApi is authorized by POST and PUT, and by UpdateRestApiPolicy for a policy: its route's own path.
        when(ctx.getMethod()).thenReturn("POST");
        request("/restapis");
        assertEquals(List.of("arn:aws:apigateway:us-east-1::/restapis"), apiGateway("CreateRestApi"));
        when(ctx.getMethod()).thenReturn("DELETE");
        request("/restapis/abc123/stages/prod/cache/data");
        assertEquals(List.of("arn:aws:apigateway:us-east-1::/restapis/abc123/stages/prod/cache/data"),
                apiGateway("FlushStageCache"));
        // A label holding the ARN of what the route acts on names that resource.
        String association = "arn:aws:apigateway:us-east-1:" + ACCOUNT + ":/domainnameaccessassociations/domainname/"
                + "private.example.com+abc123/vpcesource/vpce-0123456789abcdef0";
        request("/domainnameaccessassociations/" + URLEncoder.encode(association, StandardCharsets.UTF_8));
        assertEquals(List.of(association), apiGateway("DeleteDomainNameAccessAssociation"));
    }

    private List<String> apiGateway(String operation) {
        return builder.buildResources("apigateway", "apigateway:" + operation, ctx, "us-east-1", ACCOUNT, "aws");
    }

    /**
     * Scheduler as IAM asks it: the {@code default} schedule group, in the partition given, the one a request naming
     * no group acts on.
     */
    private static StoredResourceArns schedulerIn(String partition) {
        return new StoredResourceArns() {
            @Override
            public String namespace() {
                return "scheduler";
            }

            @Override
            public Optional<String> find(String type, List<String> names, String region) {
                return Optional.empty();
            }

            @Override
            public Optional<String> findDefault(String type, String region) {
                return "schedule-group".equals(type)
                        ? Optional.of("arn:" + partition + ":scheduler:" + region + ":" + ACCOUNT
                                + ":schedule-group/default")
                        : Optional.empty();
            }
        };
    }

    private List<String> schedulerResources(ResourceArnBuilder builder, String method, String sent, String operation) {
        when(ctx.getMethod()).thenReturn(method);
        request(sent);
        return builder.buildResources("scheduler", "scheduler:" + operation, ctx, "us-east-1", ACCOUNT, "aws");
    }

    /**
     * A schedule is in the group its request names, the body's {@code GroupName} on a create or an update and the
     * {@code groupName} query parameter otherwise, and in the {@code default} group when it names none, as botocore
     * documents ("If you omit this, the default schedule group is used"); a group is the one its path names; a tag
     * route's resource the ARN in its path; and the list operations act on no resource type.
     */
    @Test
    void aScheduleIsInTheGroupItsRequestNamesOrTheDefaultOneAndAGroupIsTheOneItsPathNames() {
        ResourceArnBuilder backed = reading(schedulerIn("aws"));
        String schedule = "arn:aws:scheduler:us-east-1:000000000000:schedule/";

        assertEquals(List.of(schedule + "default/nightly"),
                schedulerResources(backed, "GET", "/schedules/nightly", "GetSchedule"));
        assertEquals(List.of(schedule + "default/nightly"),
                schedulerResources(backed, "DELETE", "/schedules/nightly", "DeleteSchedule"));
        queryParams.putSingle("groupName", "reports");
        assertEquals(List.of(schedule + "reports/nightly"),
                schedulerResources(backed, "GET", "/schedules/nightly?groupName=reports", "GetSchedule"));
        // A create or update names its group in the body, which Scheduler reads, not in the query, which it
        // ignores for them: a groupName there does not move the check to another group. A target's ARN is no
        // schedule's.
        setJsonBody("{\"GroupName\":\"billing\",\"ScheduleExpression\":\"rate(1 hour)\",\"Target\":{\"Arn\":"
                + "\"arn:aws:lambda:us-east-1:000000000000:function:f\","
                + "\"RoleArn\":\"arn:aws:iam::000000000000:role/r\"}}");
        assertEquals(List.of(schedule + "billing/nightly"),
                schedulerResources(backed, "POST", "/schedules/nightly?groupName=reports", "CreateSchedule"));
        queryParams.clear();
        setJsonBody("{\"ScheduleExpression\":\"rate(1 hour)\"}");
        assertEquals(List.of(schedule + "default/nightly"),
                schedulerResources(backed, "PUT", "/schedules/nightly", "UpdateSchedule"));

        String group = "arn:aws:scheduler:us-east-1:000000000000:schedule-group/reports";
        assertEquals(List.of(group), schedulerResources(backed, "GET", "/schedule-groups/reports", "GetScheduleGroup"));
        assertEquals(List.of(group),
                schedulerResources(backed, "POST", "/schedule-groups/reports", "CreateScheduleGroup"));
        assertEquals(List.of(group),
                schedulerResources(backed, "DELETE", "/schedule-groups/reports", "DeleteScheduleGroup"));
        // An SDK sends the ARN in the path encoded, as one segment of the modeled /tags/{ResourceArn}.
        String encoded = "/tags/arn%3Aaws%3Ascheduler%3Aus-east-1%3A000000000000%3Aschedule-group%2Freports";
        assertEquals(List.of(group), schedulerResources(backed, "GET", encoded, "ListTagsForResource"));
        assertEquals(List.of(group), schedulerResources(backed, "POST", encoded, "TagResource"));
        queryParams.putSingle("TagKeys", "team");
        assertEquals(List.of(group), schedulerResources(backed, "DELETE", encoded + "?TagKeys=team", "UntagResource"));
        queryParams.clear();
        assertEquals(List.of("*"), schedulerResources(backed, "GET", "/schedules", "ListSchedules"));
        assertEquals(List.of("*"), schedulerResources(backed, "GET", "/schedule-groups", "ListScheduleGroups"));
    }

    @Test
    void schedulerAndPipesArnsAreMintedInTheRequestsPartition() {
        // A Region no partition publishes takes the deployment's partition, as Scheduler and Pipes mint
        // their ARNs, rather than aws.
        ResourceArnBuilder backed = reading(schedulerIn("aws-cn"));
        when(ctx.getMethod()).thenReturn("GET");
        request("/schedules/nightly");
        assertEquals(List.of("arn:aws-cn:scheduler:xx-local-1:000000000000:schedule/default/nightly"),
                backed.buildResources("scheduler", "scheduler:GetSchedule", ctx, "xx-local-1", "000000000000",
                        "aws-cn"));
        request("/schedule-groups/reports");
        assertEquals(List.of("arn:aws-cn:scheduler:xx-local-1:000000000000:schedule-group/reports"),
                backed.buildResources("scheduler", "scheduler:GetScheduleGroup", ctx, "xx-local-1", "000000000000",
                        "aws-cn"));
        request("/v1/pipes/orders");
        assertEquals(List.of("arn:aws-cn:pipes:xx-local-1:000000000000:pipe/orders"),
                builder.buildResources("pipes", "pipes:DescribePipe", ctx, "xx-local-1", "000000000000", "aws-cn"));
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

    /** A tag route names the resource by its ARN, which keeps its account and Region beside the caller's. */
    @Test
    void lambdaTagRoutesNameTheResourceTheirArnNames() {
        String shared = "arn:aws:lambda:eu-west-1:111122223333:function:shared";
        assertTrue(lambda("GET", "/2017-03-31/tags/" + shared, "ListTags").contains(shared));
        String image = "arn:aws:lambda:eu-west-1:111122223333:microvm-image:img";
        assertTrue(lambda("POST", "/2017-03-31/tags/" + image, "TagResource").contains(image));
        String mapping = "arn:aws:lambda:us-east-1:111122223333:event-source-mapping:14e0db71-5d35-4eb5-b481-8945cf9d10c2";
        assertTrue(lambda("GET", "/2017-03-31/tags/" + mapping, "ListTags").contains(mapping));
        // A target that is no Lambda ARN names no resource.
        assertEquals(List.of("*"), lambda("GET", "/2017-03-31/tags/shared", "ListTags"));
    }

    /** A Pipes route is checked on the pipe its path names, or the ARN its tagging route names, from the table. */
    @Test
    void pipesRoutesResolveThePipeOrTheTaggedArn() {
        String pipe = "arn:aws:pipes:us-east-1:000000000000:pipe/orders";
        when(ctx.getMethod()).thenReturn("GET");
        request("/v1/pipes/orders");
        assertEquals(List.of(pipe), pipes("pipes:DescribePipe"));
        when(ctx.getMethod()).thenReturn("POST");
        request("/v1/pipes/orders/start");
        assertEquals(List.of(pipe), pipes("pipes:StartPipe"));
        when(ctx.getMethod()).thenReturn("GET");
        request("/tags/" + pipe.replace(":", "%3A").replace("/", "%2F"));
        assertEquals(List.of(pipe), pipes("pipes:ListTagsForResource"));
        request("/v1/pipes");
        assertEquals(List.of("*"), pipes("pipes:ListPipes"));
    }

    private List<String> pipes(String operation) {
        return builder.buildResources("pipes", operation, ctx, "us-east-1", ACCOUNT, "aws");
    }

    /** A durable execution's actions are checked on the execution its ARN names, which a client sends encoded. */
    @Test
    void lambdaDurableExecutionRoutesNameTheExecution() {
        String execution = "arn:aws:lambda:us-east-1:000000000000:function:my-function:1/durable-execution/run-1/0b1c";
        String sent = execution.replace("/", "%2F");
        assertEquals(List.of(execution), lambda("POST", "/2025-12-01/durable-executions/" + sent + "/stop",
                "StopDurableExecution"));
        assertEquals(List.of(execution), lambda("GET", "/2025-12-01/durable-executions/" + sent,
                "GetDurableExecution"));
    }

    /** Lambda authorizes a callback request against the ARN of the durable execution that created the callback. */
    @Test
    void lambdaDurableCallbackRoutesNameTheExecutionOfTheCallback() {
        String execution = "arn:aws:lambda:us-east-1:000000000000:function:my-function:1/durable-execution/run-1/0b1c";
        String callbackId = Base64.getEncoder().encodeToString((execution + "|c1|nonce").getBytes(StandardCharsets.UTF_8));
        @SuppressWarnings("unchecked")
        Instance<LambdaMicrovmsService> none = mock(Instance.class);
        ResourceArnBuilder backed = reading(new LambdaStoredResourceArns(null, null, none, null));
        assertEquals(List.of(execution), lambda(backed, "POST",
                "/2025-12-01/durable-execution-callbacks/" + callbackId.replace("/", "%2F") + "/succeed",
                "SendDurableExecutionCallbackSuccess"));
        assertEquals(List.of("*"), lambda(backed, "POST", "/2025-12-01/durable-execution-callbacks/not-a-callback/heartbeat",
                "SendDurableExecutionCallbackHeartbeat"));
    }

    // ── EKS ──────────────────────────────────────────────────────────────────────

    /** The node group and add-on EKS holds in the cluster {@code held}, in the request's Region (us-east-1). */
    private static final String HELD_NODEGROUP = "arn:aws:eks:us-east-1:000000000000:nodegroup/held/ng/1";
    private static final String HELD_ADDON = "arn:aws:eks:us-east-1:000000000000:addon/held/vpc-cni/4";

    /** A builder reading what EKS holds through its lookup, which the table asks to find what ends in an id. */
    private static ResourceArnBuilder eksHolding() {
        EksService eks = mock(EksService.class);
        when(eks.findResourceArn(any(), any(), any())).thenReturn(Optional.empty());
        when(eks.findResourceArn("held", "node-groups", "ng")).thenReturn(Optional.of(HELD_NODEGROUP));
        when(eks.findResourceArn("held", "addons", "vpc-cni")).thenReturn(Optional.of(HELD_ADDON));
        when(eks.findResourceArn("west", "node-groups", "ng"))
                .thenReturn(Optional.of("arn:aws:eks:us-west-2:000000000000:nodegroup/west/ng/1"));
        return reading(new EksStoredResourceArns(eks, new RegionResolver("us-east-1", ACCOUNT)));
    }

    private List<String> eks(ResourceArnBuilder eks, String method, String sent, String operation) {
        when(ctx.getMethod()).thenReturn(method);
        request(sent);
        return eks.buildResources("eks", operation, ctx, "us-east-1", ACCOUNT, "aws");
    }

    /** A cluster route is checked on the cluster its path names, from the table. */
    @Test
    void anEksClusterRouteIsCheckedOnTheClusterItsPathNames() {
        ResourceArnBuilder eks = eksHolding();
        assertEquals(List.of("arn:aws:eks:us-east-1:000000000000:cluster/held"),
                eks(eks, "GET", "/clusters/held", "eks:DescribeCluster"));
        assertEquals(List.of("arn:aws:eks:us-east-1:000000000000:cluster/held"),
                eks(eks, "GET", "/clusters/held/node-groups", "eks:ListNodegroups"));
        // ListClusters is checked on every cluster of the account, as measured on AWS (MEASURED, #9039).
        assertEquals(List.of("arn:aws:eks:us-east-1:000000000000:cluster/*"),
                eks(eks, "GET", "/clusters", "eks:ListClusters"));
    }

    /** CreateCluster is checked on the cluster its body names, as the EKS user guide's example policy grants it. */
    @Test
    void anEksCreateClusterIsCheckedOnTheClusterItsBodyNames() {
        setJsonBody("{\"name\":\"new\",\"roleArn\":\"arn:aws:iam::000000000000:role/eks\"}");
        assertEquals(List.of("arn:aws:eks:us-east-1:000000000000:cluster/new"),
                eks(eksHolding(), "POST", "/clusters", "eks:CreateCluster"));
    }

    /**
     * A node group or an add-on, whose ARN ends in an id EKS minted, is checked on the one EKS holds under the names
     * its path gives, found in the request's Region; one EKS holds none of, or holds in another Region, has its ARN's
     * names open.
     */
    @Test
    void anEksNodegroupOrAddonIsCheckedOnTheOneEksHolds() {
        ResourceArnBuilder eks = eksHolding();
        assertEquals(List.of(HELD_NODEGROUP),
                eks(eks, "GET", "/clusters/held/node-groups/ng", "eks:DescribeNodegroup"));
        assertEquals(List.of(HELD_ADDON), eks(eks, "GET", "/clusters/held/addons/vpc-cni", "eks:DescribeAddon"));
        List<String> missing = eks(eks, "GET", "/clusters/held/node-groups/gone", "eks:DescribeNodegroup");
        assertEquals(1, missing.size());
        assertTrue(missing.getFirst().startsWith("arn:aws:eks:us-east-1:000000000000:nodegroup/"), missing.toString());
        assertTrue(missing.getFirst().endsWith("*"), missing.toString());
        assertEquals(missing, eks(eks, "GET", "/clusters/west/node-groups/ng", "eks:DescribeNodegroup"));
    }

    /** A tagging route is checked on the ARN its modeled path carries. */
    @Test
    void anEksTaggingRouteIsCheckedOnTheArnItCarries() {
        String cluster = "arn:aws:eks:us-east-1:000000000000:cluster/held";
        String encoded = cluster.replace(":", "%3A").replace("/", "%2F");
        assertEquals(List.of(cluster), eks(eksHolding(), "GET", "/tags/" + encoded, "eks:ListTagsForResource"));
    }

    /** Stubs the request path as sent and, decoded, as {@link UriInfo#getPath()} gives it. */
    private void request(String sent) {
        when(uriInfo.getRequestUri()).thenReturn(URI.create("http://localhost:4566" + sent));
        when(uriInfo.getBaseUri()).thenReturn(URI.create("http://localhost:4566/"));
        when(uriInfo.getPath()).thenReturn(URI.create(sent).getPath());
    }
}
