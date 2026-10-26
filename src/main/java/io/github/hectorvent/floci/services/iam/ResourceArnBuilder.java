package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsQueryServiceResolver;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.RequestResources;
import io.github.hectorvent.floci.core.common.ServicePrincipals;
import io.github.hectorvent.floci.core.common.SigV4CredentialScope;
import io.github.hectorvent.floci.core.common.StoredResourceArns;
import io.github.hectorvent.floci.services.cloudwatch.logs.LogGroupTagTarget;
import io.github.hectorvent.floci.services.eks.EksController;
import io.github.hectorvent.floci.services.eks.EksService;
import io.github.hectorvent.floci.services.kms.KmsService;
import io.github.hectorvent.floci.services.lambda.LambdaArnUtils;
import io.github.hectorvent.floci.services.lambda.durable.DurableTokens;
import io.github.hectorvent.floci.services.lambdamicrovms.LambdaMicrovmsService;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Constructs the target resource ARN for a request so the policy evaluator
 * can match it against Resource patterns in policy documents.
 *
 * Returns {@code *} when the resource cannot be determined, which matches
 * permissive wildcard policies.
 */
@ApplicationScoped
public class ResourceArnBuilder {

    private static final Pattern ACCESS_POLICY_ARN = Pattern.compile(EksController.ACCESS_POLICY_ARN);

    /** The only scheme IAM accepts on an OIDC provider URL, and the part its ARN drops. */
    private static final String OIDC_URL_SCHEME = "https://";

    private static final String DELEGATION_REQUEST_RESOURCE = "delegation-request/";

    /** What a service-linked role deletion task identifier starts with; see its resolver. */
    private static final String DELETION_TASK_PREFIX = "task" + ServiceLinkedRoles.PATH;

    /**
     * Stands in for the random suffix of a secret that {@code CreateSecret} has not made yet. Each
     * {@code ?} matches the one character a {@code secret:<name>-??????} policy expects there.
     */
    private static final String UNASSIGNED_SECRET_SUFFIX = "-??????";

    private final ObjectMapper objectMapper;
    /**
     * Looked up lazily through a provider: this builder is constructed by the enforcement filter,
     * which IamService itself does not depend on, and a direct injection would close that loop.
     */
    private final Instance<IamService> iamService;
    /** Looked up lazily for the same reason, to resolve a secret named by its name or a partial ARN. */
    private final Instance<SecretsManagerService> secretsManagerService;
    /** Looked up lazily for the same reason, to read the image a MicroVM runs. */
    private final Instance<LambdaMicrovmsService> microvmsService;
    /** Looked up lazily for the same reason, to read the ARN an EKS cluster or what it holds is stored under. */
    private final Instance<EksService> eksService;
    /** Looked up lazily for the same reason, to resolve a key named by an id, an alias or an ARN to its stored ARN. */
    private final Instance<KmsService> kmsService;
    /**
     * Looked up lazily for the same reason, to read the ARN a resource the request-resources table names by a name
     * its service follows with a minted id is stored under.
     */
    private final Instance<StoredResourceArns> storedResourceArns;

    @Inject
    public ResourceArnBuilder(ObjectMapper objectMapper, Instance<IamService> iamService,
                              Instance<SecretsManagerService> secretsManagerService,
                              Instance<LambdaMicrovmsService> microvmsService,
                              Instance<EksService> eksService,
                              Instance<KmsService> kmsService,
                              Instance<StoredResourceArns> storedResourceArns) {
        this.objectMapper = objectMapper;
        this.iamService = iamService;
        this.secretsManagerService = secretsManagerService;
        this.microvmsService = microvmsService;
        this.eksService = eksService;
        this.kmsService = kmsService;
        this.storedResourceArns = storedResourceArns;
    }

    public ResourceArnBuilder(ObjectMapper objectMapper, Instance<IamService> iamService,
                              Instance<SecretsManagerService> secretsManagerService,
                              Instance<LambdaMicrovmsService> microvmsService,
                              Instance<EksService> eksService,
                              Instance<KmsService> kmsService) {
        this(objectMapper, iamService, secretsManagerService, microvmsService, eksService, kmsService, null);
    }

    public ResourceArnBuilder(ObjectMapper objectMapper, Instance<IamService> iamService,
                              Instance<SecretsManagerService> secretsManagerService,
                              Instance<LambdaMicrovmsService> microvmsService,
                              Instance<EksService> eksService) {
        this(objectMapper, iamService, secretsManagerService, microvmsService, eksService, null);
    }

    public ResourceArnBuilder(ObjectMapper objectMapper, Instance<IamService> iamService,
                              Instance<SecretsManagerService> secretsManagerService,
                              Instance<LambdaMicrovmsService> microvmsService) {
        this(objectMapper, iamService, secretsManagerService, microvmsService, null);
    }

    public ResourceArnBuilder(ObjectMapper objectMapper, Instance<IamService> iamService,
                              Instance<SecretsManagerService> secretsManagerService) {
        this(objectMapper, iamService, secretsManagerService, null);
    }

    ResourceArnBuilder(ObjectMapper objectMapper, Instance<IamService> iamService) {
        this(objectMapper, iamService, null);
    }

    public ResourceArnBuilder() {
        this(new ObjectMapper(), null, null);
    }

    public ResourceArnBuilder(ObjectMapper objectMapper) {
        this(objectMapper, null, null);
    }

    public String build(String credentialScope, ContainerRequestContext ctx,
                        String region, String accountId) {
        List<String> list = buildResources(credentialScope, ctx, region, accountId);
        return list.isEmpty() ? "*" : list.getFirst();
    }

    public List<String> buildResources(String credentialScope, ContainerRequestContext ctx,
                                       String region, String accountId) {
        return buildResources(credentialScope, ctx, region, accountId, AwsRegions.partitionFor(region));
    }

    /**
     * The resources a request is checked against. Scheduler, Pipes and DSQL ARNs are minted in
     * {@code partition}, the request's, as those services mint theirs, so a Region no partition publishes gets
     * the deployment's partition rather than {@code aws}.
     */
    public List<String> buildResources(String credentialScope, ContainerRequestContext ctx,
                                       String region, String accountId, String partition) {
        return buildResources(credentialScope, null, ctx, region, accountId, partition);
    }

    /**
     * The resources a request is checked against, {@code operation} the one it is dispatched as, as
     * {@code <namespace>:<Operation>}: a namespace with no branch here is checked on the resources its request
     * names for that operation (see {@link RequestResources}), and on {@code *} without one.
     */
    public List<String> buildResources(String credentialScope, String operation, ContainerRequestContext ctx,
                                       String region, String accountId, String partition) {
        if (credentialScope == null) {
            return List.of("*");
        }
        String path = ctx.getUriInfo().getPath();
        return switch (credentialScope) {
            case "s3"             -> List.of(buildS3Arn(path, region));
            case "lambda"         -> List.of(buildLambdaArn(path, ctx, region, accountId));
            case "sqs"            -> List.of(buildSqsArn(ctx, region, accountId));
            case "sns"            -> List.of(buildSnsArn(ctx, region, accountId));
            case "dynamodb"       -> buildDynamoDbArns(ctx, region, accountId);
            case "kinesis"        -> List.of(buildKinesisArn(ctx, region, accountId));
            case "ssm"            -> List.of(buildSsmArn(ctx, region, accountId));
            case "iam"            -> buildIamArns(ctx, region, accountId);
            case "resource-groups" -> List.of(buildResourceGroupArn(ctx, region, accountId));
            case "scheduler"      -> List.of(buildSchedulerArn(path, ctx, region, accountId, partition));
            case "pipes"          -> List.of(buildPipesArn(path, ctx, region, accountId, partition));
            case "dsql"           -> List.of(buildDsqlArn(path, ctx, region, accountId, partition));
            case "eks"            -> List.of(buildEksArn(path, ctx, region, accountId));
            default               -> namedResources(credentialScope, operation, ctx, region, accountId, partition);
        };
    }

    /**
     * The resources a request of a namespace this builder has no branch for names, from the vendored table of
     * request resources (see {@link RequestResources}); {@code *} when it names none, or the operation is not one
     * of the namespace. A resource whose service mints an id after its name is the one the namespace's
     * {@link StoredResourceArns} finds under that name.
     */
    private List<String> namedResources(String credentialScope, String operation, ContainerRequestContext ctx,
                                        String region, String accountId, String partition) {
        List<String> named = operation == null || !operation.startsWith(credentialScope + ":") ? List.of()
                : RequestResources.of(operation, ctx, partition, region, accountId, storedResourceArns(credentialScope));
        return named.isEmpty() ? List.of("*") : named;
    }

    /** The registered finder of the namespace's stored ARNs; null when there is none. */
    private StoredResourceArns storedResourceArns(String namespace) {
        if (storedResourceArns == null || storedResourceArns.isUnsatisfied()) {
            return null;
        }
        for (StoredResourceArns stored : storedResourceArns) {
            if (stored.namespace().equals(namespace)) {
                return stored;
            }
        }
        return null;
    }

    private String buildResourceGroupArn(ContainerRequestContext ctx, String region, String accountId) {
        JsonNode body = readJsonBody(ctx);
        if (body == null) {
            return "*";
        }
        String path = ctx.getUriInfo().getPath();
        boolean create = path.endsWith("/groups") || path.endsWith("/groups/");
        String group = create ? body.path("Name").asText()
                : body.path("Group").asText(body.path("GroupName").asText());
        if (group.isEmpty()) {
            return "*";
        }
        return group.startsWith("arn:") ? group
                : AwsArnUtils.Arn.of("resource-groups", region, accountId, "group/" + group).toString();
    }

    /** Uses the serving operation and the same raw names as the handler. */
    public List<String> buildLogResources(String action, ContainerRequestContext ctx,
                                          String region, String accountId, String partition) {
        boolean stream = "logs:CreateLogStream".equals(action) || "logs:DeleteLogStream".equals(action);
        boolean group = switch (action) {
            case "logs:CreateLogGroup", "logs:DeleteLogGroup", "logs:PutRetentionPolicy",
                    "logs:DeleteRetentionPolicy" -> true;
            default -> false;
        };
        if (!stream && !group && !LogGroupTagTarget.supports(action)) {
            return List.of("*");
        }
        JsonNode body = readJsonBody(ctx);
        if (body == null) {
            return List.of("*");
        }
        if (LogGroupTagTarget.supports(action)) {
            return List.of(LogGroupTagTarget.resolve(body.path("resourceArn").asText(), region, accountId, partition).arn());
        }
        String name = body.path("logGroupName").asText();
        String resource = "log-group:" + name + (stream
                ? ":log-stream:" + body.path("logStreamName").asText() : ":*");
        return List.of(new AwsArnUtils.Arn(partition, "logs", region, accountId, resource).toString());
    }

    // ── IAM ─────────────────────────────────────────────────────────────────────

    /** A stored entity's ARN and path, the two things a target ARN is built from. */
    private record StoredEntity(String arn, String path) {}

    /**
     * The request parameter carrying the name of the target, per resource type, for an action on a
     * resource that already exists.
     *
     * <p>Which type an action is authorized against is not decided here: it comes per action from
     * {@link IamActionResources}, which vendors AWS's own authorization metadata, because the
     * resource an IAM action acts on is not in the request. {@code AddUserToGroup} carries a
     * {@code UserName} and a {@code GroupName} and is authorized against the group,
     * {@code AddRoleToInstanceProfile} carries a {@code RoleName} and is authorized against the
     * instance profile, and the MFA actions that take a {@code SerialNumber} are authorized
     * against the user that device belongs to. Keeping the type in vendored data and only the
     * parameter here is what lets {@code make iam-action-resources-verify} check the half that is
     * AWS's against AWS.
     *
     * <p>An action whose type is not in here, and which is not a create or one of the ARN-carrying
     * actions, is evaluated against {@code *}. That is right for the types the reference gives
     * actions Floci does not dispatch, which {@link #UNRESOLVED_TYPES} names.
     */
    static final Map<String, String> NAME_PARAMETERS = Map.of(
            "user", "UserName",
            "role", "RoleName",
            "group", "GroupName",
            "instance-profile", "InstanceProfileName",
            "server-certificate", "ServerCertificateName");

    /**
     * The actions that create their target, with the parameter naming it. Their ARN is minted from
     * the request rather than read back, since there is no stored record yet, and a create is
     * checked against the {@code Path} it asks for, because reading a stored path would authorize
     * a create into a path the policy does not name.
     *
     * <p>Keyed by action rather than by type because a create spells the name its own way:
     * {@code CreateSAMLProvider} takes {@code Name} and {@code CreateOpenIDConnectProvider} takes
     * {@code Url}, where the others take the type's usual parameter. The type itself still comes
     * from the vendored metadata.
     *
     * <p>Package-private, as the other tables here are, so {@code IamActionResourcesTest} can
     * work out which resource types this class covers instead of restating them, which is what
     * catches both a create AWS gives no single type to mint and a type nothing resolves.
     */
    static final Map<String, String> CREATE_PARAMETERS = Map.of(
            "CreateUser", "UserName",
            "CreateRole", "RoleName",
            "CreateGroup", "GroupName",
            "CreateInstanceProfile", "InstanceProfileName",
            "UploadServerCertificate", "ServerCertificateName",
            "CreatePolicy", "PolicyName",
            "CreateVirtualMFADevice", "VirtualMFADeviceName",
            "CreateSAMLProvider", "Name",
            "CreateOpenIDConnectProvider", "Url");

    /**
     * The resource types AWS gives IAM actions that Floci does not resolve, with why. Every one
     * belongs to an action the handler does not dispatch, so there is no request to name a
     * resource for.
     *
     * <p>Empty since {@code GetRoleTemplateVersion} was dispatched: {@code role-template} was the
     * last entry and the action carries its ARN, so the type is resolved through
     * {@link #IAM_ARN_PARAMETERS} like any other the request names. The map stays because the next
     * action AWS publishes a type for arrives before Floci dispatches it.
     *
     * <p>Declared rather than implied so that a type which is neither resolved nor listed here
     * fails a test naming it, instead of quietly putting that action back on {@code *}. The
     * resolver's fallback for a type it does not know is the permissive answer, so a regeneration
     * that introduces one is only visible if something asserts the set.
     *
     * <p>Package-private because the reason each entry gives is a claim about the handler, not
     * about this class: {@code UnresolvedResourceTypesIntegrationTest} sends each of these actions
     * and holds it to answering {@code UnsupportedOperation}. Dispatching one without resolving
     * its type would leave it evaluated against {@code *}, which is the fail-open this class
     * exists to close.
     */
    static final Map<String, String> UNRESOLVED_TYPES = Map.of();

    /**
     * The IAM actions whose target the request carries as an ARN, and the parameter holding it.
     * Passed through rather than rebuilt: the ARN is the caller's own naming of the resource, and
     * an action naming one that does not exist fails as {@code NoSuchEntity} regardless of what a
     * policy says.
     *
     * <p>This is also the answer for every action AWS gives more than one resource type. The four
     * that take a principal ARN are authorized against whichever of user, role or group that ARN
     * names, and {@code DeleteVirtualMFADevice} against either {@code mfa} or {@code sms-mfa}, so
     * the parameter is the resource and there is nothing to choose.
     */
    static final Map<String, String> IAM_ARN_PARAMETERS = iamArnParameters();

    /**
     * The actions resolved by a branch of their own in {@link #buildIamArns}, because what the
     * request carries is not an IAM entity's name: a service principal for
     * {@code CreateServiceLinkedRole}, an access key id for {@code GetAccessKeyLastUsed}, a
     * deletion task id for {@code GetServiceLinkedRoleDeletionStatus}, an Organizations
     * entity path for {@code GenerateOrganizationsAccessReport}, whose resource lives in another
     * service's tree, and a request id for the five delegation actions that name one.
     *
     * <p>The resolver is the value rather than a branch elsewhere, so this cannot become a list
     * that says an action is handled while the dispatch has nothing for it. The keys are also what
     * {@code IamActionResourcesTest} reads to tell a type one of these covers from a type nothing
     * resolves.
     */
    static final Map<String, DerivedResolver> DERIVED_RESOLVERS = Map.of(
            "CreateServiceLinkedRole", ResourceArnBuilder::serviceLinkedRoleArn,
            "GetAccessKeyLastUsed",
            (self, ctx, region, accountId) -> self.accessKeyOwnerArn(ctx, accountId),
            "GetServiceLinkedRoleDeletionStatus", ResourceArnBuilder::deletionTaskRoleArn,
            "GenerateOrganizationsAccessReport",
            ResourceArnBuilder::organizationsAccessReportArn,
            "AssociateDelegationRequest", ResourceArnBuilder::delegationRequestArn,
            "AcceptDelegationRequest", ResourceArnBuilder::delegationRequestArn,
            "RejectDelegationRequest", ResourceArnBuilder::delegationRequestArn,
            "GetDelegationRequest", ResourceArnBuilder::delegationRequestArn,
            "SendDelegationToken", ResourceArnBuilder::delegationRequestArn,
            "GetHumanReadableSummary", ResourceArnBuilder::summarizedRequestArn);

    /** How one of {@link #DERIVED_RESOLVERS} reads its resource out of the request. */
    interface DerivedResolver {
        List<String> resolve(ResourceArnBuilder self, ContainerRequestContext ctx, String region,
                             String accountId);
    }

    /**
     * The actions that rename or move their target, and the parameter carrying the new name. These
     * name two resources, because AWS requires the principal to be allowed on both: UpdateUser's
     * reference says the requester "must have appropriate permissions on both the source object
     * and the target object", and UpdateGroup's that a principal allowed to update the old group
     * but not the new one has the update fail. The filter authorizes a request once per resource,
     * so naming both is what enforces it.
     */
    private static final Map<String, String> IAM_RENAME_PARAMETERS = Map.of(
            "UpdateUser", "NewUserName",
            "UpdateGroup", "NewGroupName",
            "UpdateServerCertificate", "NewServerCertificateName");

    private static Map<String, String> iamArnParameters() {
        Map<String, String> parameters = new LinkedHashMap<>();
        arnParameter(parameters, "PolicyArn",
                "CreatePolicyVersion", "DeletePolicy", "DeletePolicyVersion", "GetPolicy",
                "GetPolicyVersion", "ListEntitiesForPolicy", "ListPolicyTags", "ListPolicyVersions",
                "SetDefaultPolicyVersion", "TagPolicy", "UntagPolicy");
        arnParameter(parameters, "SerialNumber",
                "DeleteVirtualMFADevice", "ListMFADeviceTags", "TagMFADevice", "UntagMFADevice");
        arnParameter(parameters, "SAMLProviderArn",
                "DeleteSAMLProvider", "GetSAMLProvider", "ListSAMLProviderTags", "TagSAMLProvider",
                "UntagSAMLProvider", "UpdateSAMLProvider");
        arnParameter(parameters, "OpenIDConnectProviderArn",
                "AddClientIDToOpenIDConnectProvider", "DeleteOpenIDConnectProvider",
                "GetOpenIDConnectProvider", "ListOpenIDConnectProviderTags",
                "RemoveClientIDFromOpenIDConnectProvider", "TagOpenIDConnectProvider",
                "UntagOpenIDConnectProvider", "UpdateOpenIDConnectProviderThumbprint");
        arnParameter(parameters, "Arn",
                "GenerateServiceLastAccessedDetails", "ListPoliciesGrantingServiceAccess");
        arnParameter(parameters, "PolicySourceArn",
                "GetContextKeysForPrincipalPolicy", "SimulatePrincipalPolicy");
        // AWS owns role templates, so the ARN names one in the aws account rather than the
        // caller's, and it is still the resource the action is authorized against.
        arnParameter(parameters, "TemplateArn", "GetRoleTemplateVersion");
        return Map.copyOf(parameters);
    }

    private static void arnParameter(Map<String, String> parameters, String parameter,
                                     String... actions) {
        for (String action : actions) {
            parameters.put(action, parameter);
        }
    }

    /**
     * The resource ARN an IAM action is evaluated against, so a policy statement naming one user,
     * role, group, profile, policy, provider, MFA device or certificate binds to it and to nothing
     * else. Everything this cannot name resolves to {@code *}, which is the permissive answer and
     * the one AWS gives for the actions that take no resource.
     *
     * <p>The operation is resolved through {@link AwsQueryServiceResolver#action(String, String)},
     * so the legacy {@code Operation} parameter names the resource exactly as {@code Action} does.
     * Reading {@code Action} alone would let a caller spell the operation the other way and have
     * the request evaluated against {@code *}, skipping a deny that names the resource.
     *
     * <p>An action that defaults to the calling user when its {@code UserName} is omitted, as
     * {@code GetUser} and {@code ChangePassword} do, resolves to that user: the access key id in
     * the credential names the identity, and the stored ARN carries its path and partition. The
     * wildcard stands only when the signer is not an IAM user, an account's root stand-in and a
     * session credential having no user record to name.
     */
    private List<String> buildIamArns(ContainerRequestContext ctx, String region,
                                      String accountId) {
        String action = AwsQueryServiceResolver.action(
                RequestBodyReader.formField(ctx, "Action"),
                RequestBodyReader.formField(ctx, "Operation"));
        if (action == null) {
            return List.of("*");
        }
        String arnParameter = IAM_ARN_PARAMETERS.get(action);
        if (arnParameter != null) {
            // Only an ARN is passed through. A hardware MFA device's SerialNumber is a bare serial
            // rather than an ARN, and naming the request after it would produce a resource no
            // policy can be written against.
            String arn = RequestBodyReader.formField(ctx, arnParameter);
            return arn == null || !AwsArnUtils.isArn(arn) ? List.of("*") : List.of(arn);
        }
        DerivedResolver derived = DERIVED_RESOLVERS.get(action);
        if (derived != null) {
            return derived.resolve(this, ctx, region, accountId);
        }
        String createParameter = CREATE_PARAMETERS.get(action);
        if (createParameter != null) {
            // A create names a resource that does not exist yet, so its ARN is minted from the
            // request: the path it asks for, in the partition it is being created in. Reading a
            // stored path here would authorize a create under /team/ against the root path.
            String created = RequestBodyReader.formField(ctx, createParameter);
            List<String> createdTypes = IamActionResources.typesOf(action);
            // One type is what there is to mint. A create AWS authorizes against none, or against
            // several, has no single ARN to build, so the wildcard stands rather than the request
            // failing on it; IamActionResourcesTest is what says so out loud.
            if (created == null || created.isBlank() || createdTypes.size() != 1) {
                return List.of("*");
            }
            return List.of(mintedIamArn(createdTypes.get(0), created, ctx, region, accountId));
        }
        // Everything else is an action on a resource that exists, named by name. Which type that
        // is comes from AWS's own metadata, and the parameter the name arrives in comes from the
        // type: an action AWS gives a type this resolver has no parameter for, or more than one
        // type, is left at the wildcard rather than guessed at.
        String resourceType = nameableType(action);
        if (resourceType == null) {
            return List.of("*");
        }
        String name = RequestBodyReader.formField(ctx, NAME_PARAMETERS.get(resourceType));
        if (name == null || name.isBlank()) {
            // Nineteen user actions make UserName optional, and AWS then acts on the user who
            // signed the request, so that user is the resource the request names. Resolving it
            // here is what lets a statement scoped to one user reach GetUser, ListAccessKeys and
            // the rest when they are called the way the console calls them, with no UserName.
            return "user".equals(resourceType) ? signingUserArn(ctx, accountId) : List.of("*");
        }
        // Every other action acts on a resource that already exists, so the check uses that
        // resource's own stored ARN instead of deriving one again. The stored ARN carries the path
        // and, more to the point, the partition the resource was created in: a resource stays in
        // its partition, so re-minting from the caller's signing region would name an ARN that no
        // resource has and leave a deny on the real one unmatched.
        Optional<StoredEntity> stored = storedIamEntity(resourceType, name, accountId);
        if (stored.isEmpty()) {
            return List.of("*");
        }
        String storedArn = stored.get().arn();
        String renameParameter = IAM_RENAME_PARAMETERS.get(action);
        if (renameParameter == null) {
            return List.of(storedArn);
        }
        String renamed = renameTargetArn(ctx, stored.get(), resourceType, name,
                renameParameter, region, accountId);
        return storedArn.equals(renamed) ? List.of(storedArn) : List.of(storedArn, renamed);
    }

    /**
     * The ARN a rename or a move would produce, built beside the stored ARN so it keeps the
     * resource's partition and account. Only the path and the name change, and taking the
     * partition from the caller's signing region instead would name a resource in a partition the
     * original does not live in. Returns the stored ARN unchanged when the request renames and
     * moves nothing.
     */
    private String renameTargetArn(ContainerRequestContext ctx, StoredEntity stored,
                                   String resourceType, String name, String renameParameter,
                                   String region, String accountId) {
        String newName = RequestBodyReader.formField(ctx, renameParameter);
        String newPath = RequestBodyReader.formField(ctx, "NewPath");
        String targetName = newName == null || newName.isBlank() ? name : newName;
        String targetPath = newPath == null || newPath.isBlank()
                ? normalizeArnPath(stored.path())
                : normalizeArnPath(newPath);
        String partition = AwsArnUtils.partitionOrDefault(stored.arn(),
                AwsRegions.partitionFor(region));
        String account = AwsArnUtils.accountOrDefault(stored.arn(), accountId);
        return AwsArnUtils.Arn.global(partition, "iam", account,
                resourceType + targetPath + targetName).toString();
    }

    /**
     * The ARN of a resource the request is creating. IAM is global, so it carries no region, and
     * it is minted in the request's partition rather than through a blank-region {@code Arn.of},
     * which would silently mean the commercial one.
     */
    private String mintedIamArn(String resourceType, String name, ContainerRequestContext ctx,
                                String region, String accountId) {
        String resource = switch (resourceType) {
            // A SAML provider's ARN is its bare name and an OIDC provider's is its URL without
            // the scheme, as IAM stores it. Neither format has a path, so Path is not read here.
            case "saml-provider" -> "saml-provider/" + name;
            case "oidc-provider" -> "oidc-provider/" + oidcProviderName(name);
            default -> resourceType
                    + normalizeArnPath(RequestBodyReader.formField(ctx, "Path")) + name;
        };
        return AwsArnUtils.Arn.global(AwsRegions.partitionFor(region), "iam", accountId, resource)
                .toString();
    }

    /**
     * The delegation request a by-id action names.
     *
     * <p>Minted from the request rather than read back: the id is the whole resource name, with no
     * path and no partition of its own to recover, and an id naming no request answers
     * {@code NoSuchEntity} whatever a policy says. That is the same reasoning the ARN-carrying
     * actions use, one step earlier, because this family passes an id where they pass an ARN.
     *
     * <p>{@code CreateDelegationRequest} is not here: IAM mints the id, so the request names no
     * resource and the wildcard is the honest answer. {@code UpdateDelegationRequest} is not here
     * either, AWS publishing no authorizable action for it at all, so it carries no type to
     * resolve.
     */
    private List<String> delegationRequestArn(ContainerRequestContext ctx, String region,
                                              String accountId) {
        String id = RequestBodyReader.formField(ctx, "DelegationRequestId");
        if (id == null || id.isBlank()) {
            return List.of("*");
        }
        return List.of(delegationRequestArn(region, accountId, id));
    }

    /**
     * The delegation request a summary is asked for, named by the ARN Floci mints rather than
     * the one the caller spelled.
     *
     * <p>{@code GetHumanReadableSummary} is the one action of this family that takes an ARN, and
     * the lookup reads only the request id out of it, deliberately: the id is what resolves a
     * request across accounts. So the string as it arrived cannot be what the request is
     * authorized against. A caller allowed by a wildcard and denied on one request could
     * otherwise respell that request's ARN, under another account or another service, and miss
     * the deny while still reading the summary. The id is lifted out and the canonical ARN is
     * minted around it instead, which is the same ARN the five by-id actions resolve to.
     *
     * <p>A value that is no ARN, or an ARN naming anything but an IAM delegation request, leaves
     * the wildcard standing: the service answers {@code NOT_SUPPORTED} or {@code InvalidInput}
     * for those without reading a request at all.
     */
    private List<String> summarizedRequestArn(ContainerRequestContext ctx, String region,
                                              String accountId) {
        return AwsArnUtils
                .resourceIfArnFor(RequestBodyReader.formField(ctx, "EntityArn"), "iam")
                .filter(resource -> resource.startsWith(DELEGATION_REQUEST_RESOURCE))
                .map(resource -> resource.substring(DELEGATION_REQUEST_RESOURCE.length()))
                .filter(id -> !id.isBlank())
                .map(id -> List.of(delegationRequestArn(region, accountId, id)))
                .orElseGet(() -> List.of("*"));
    }

    /** The ARN a delegation request carries, which is its id under the calling account. */
    private static String delegationRequestArn(String region, String accountId, String id) {
        return AwsArnUtils.Arn.global(AwsRegions.partitionFor(region), "iam", accountId,
                DELEGATION_REQUEST_RESOURCE + id).toString();
    }

    /** An OIDC provider URL as its ARN carries it, which is the URL without its scheme. */
    private static String oidcProviderName(String url) {
        return url.startsWith(OIDC_URL_SCHEME) ? url.substring(OIDC_URL_SCHEME.length()) : url;
    }

    /**
     * The role {@code CreateServiceLinkedRole} would create. Its name is not in the request: AWS
     * derives it from {@code AWSServiceName}, so the table the create itself reads is read here
     * too, and the path is the service-role path carrying the canonical principal. A service with
     * no entry names no role, leaving nothing to authorize against but {@code *}.
     */
    private List<String> serviceLinkedRoleArn(ContainerRequestContext ctx, String region,
                                              String accountId) {
        String serviceName = RequestBodyReader.formField(ctx, "AWSServiceName");
        if (serviceName == null || serviceName.isBlank()) {
            return List.of("*");
        }
        Optional<String> baseName = ServiceLinkedRoles.roleName(serviceName);
        if (baseName.isEmpty()) {
            return List.of("*");
        }
        String suffix = RequestBodyReader.formField(ctx, "CustomSuffix");
        String roleName = baseName.get()
                + (suffix == null || suffix.isBlank() ? "" : "_" + suffix);
        String path = ServiceLinkedRoles.PATH + ServicePrincipals.canonical(serviceName) + "/";
        return List.of(AwsArnUtils.Arn.global(AwsRegions.partitionFor(region), "iam", accountId,
                "role" + path + roleName).toString());
    }

    /**
     * The role {@code GetServiceLinkedRoleDeletionStatus} asks about. The request names no role,
     * but the deletion task does: the action's own reference gives the identifier's format as
     * {@code task/aws-service-role/<service-principal-name>/<role-name>/<task-uuid>}, which is the
     * role's path and name with a prefix in front and a uuid behind, and that is the shape
     * {@code DeleteServiceLinkedRole} mints. Derived rather than looked up because by the time
     * this action is called the role is gone, so there is no stored ARN left to read.
     */
    private List<String> deletionTaskRoleArn(ContainerRequestContext ctx, String region,
                                             String accountId) {
        String taskId = RequestBodyReader.formField(ctx, "DeletionTaskId");
        if (taskId == null || !taskId.startsWith(DELETION_TASK_PREFIX)) {
            return List.of("*");
        }
        int uuidSeparator = taskId.lastIndexOf('/');
        if (uuidSeparator <= DELETION_TASK_PREFIX.length()) {
            return List.of("*");
        }
        String pathAndName = taskId.substring("task".length(), uuidSeparator);
        return List.of(AwsArnUtils.Arn.global(AwsRegions.partitionFor(region), "iam", accountId,
                "role" + pathAndName).toString());
    }

    /**
     * The user who owns the access key {@code GetAccessKeyLastUsed} asks about. The action is
     * evaluated against a user, which is what the IAM User Guide's own {@code iam:*AccessKey*}
     * example scopes to {@code user/${aws:username}}, but the request names only the key, so the
     * owner is resolved from it.
     */
    private List<String> accessKeyOwnerArn(ContainerRequestContext ctx, String accountId) {
        String accessKeyId = RequestBodyReader.formField(ctx, "AccessKeyId");
        if (accessKeyId == null || accessKeyId.isBlank() || iamService == null) {
            return List.of("*");
        }
        return iamService.get().findAccessKeyOwnerInThisAccount(accessKeyId)
                .flatMap(userName -> storedIamEntity("user", userName, accountId))
                .map(stored -> List.of(stored.arn()))
                .orElse(List.of("*"));
    }

    /**
     * The user who signed the request, for the actions whose {@code UserName} is optional. The
     * credential's own key names the identity, which is the same lookup
     * {@code GetAccessKeyLastUsed} makes on a key the request carries as a parameter.
     *
     * <p>The wildcard stands when the signer is not an IAM user: an account's root stand-in and a
     * session credential have no user record, and AWS evaluates those against the identity they
     * belong to rather than against a user ARN this resolver could mint.
     */
    private List<String> signingUserArn(ContainerRequestContext ctx, String accountId) {
        if (iamService == null) {
            return List.of("*");
        }
        Optional<String> accessKeyId = SigV4CredentialScope.accessKeyId(ctx.getHeaderString("Authorization"));
        Optional<List<String>> user = accessKeyId
                .flatMap(key -> iamService.get().findUserNameByAccessKeyId(key))
                .flatMap(userName -> storedIamEntity("user", userName, accountId))
                .map(stored -> List.of(stored.arn()));
        if (user.isPresent()) {
            return user.get();
        }
        // A caller that is the account root names the root user, which is what these actions act
        // on when they are called with no UserName. AWS's own task policy for auditing root
        // credentials is written against arn:aws:iam::*:root, so a privileged session calling
        // GetUser or ListAccessKeys has to name that resource rather than the wildcard.
        return accessKeyId.flatMap(key -> iamService.get().resolveCallerArn(key))
                .filter(arn -> arn.endsWith(":root"))
                .map(List::of)
                .orElse(List.of("*"));
    }

    /**
     * The report {@code GenerateOrganizationsAccessReport} would produce. The reference spells the
     * type as {@code access-report/${EntityPath}}, so the resource is the Organizations entity
     * path the request carries and there is no IAM {@code Path} to read: the slashes inside it are
     * the organization's own tree, not an IAM path.
     */
    private List<String> organizationsAccessReportArn(ContainerRequestContext ctx, String region,
                                                      String accountId) {
        String entityPath = RequestBodyReader.formField(ctx, "EntityPath");
        if (entityPath == null || entityPath.isBlank()) {
            return List.of("*");
        }
        return List.of(AwsArnUtils.Arn.global(AwsRegions.partitionFor(region), "iam", accountId,
                "access-report/" + entityPath).toString());
    }

    /** A request's Path as it appears in an ARN: slash-delimited, defaulting to a bare slash. */
    private static String normalizeArnPath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String normalized = path.startsWith("/") ? path : "/" + path;
        return normalized.endsWith("/") ? normalized : normalized + "/";
    }

    /**
     * The resource type an action names by name, or null when there is none to use: AWS gives the
     * action no type, gives it more than one, or gives it a type this resolver has no name
     * parameter for. More than one type always means the request carries an ARN, which the caller
     * handles before reaching here.
     */
    private static String nameableType(String action) {
        List<String> types = IamActionResources.typesOf(action);
        if (types.size() != 1) {
            return null;
        }
        String resourceType = types.get(0);
        return NAME_PARAMETERS.containsKey(resourceType) ? resourceType : null;
    }

    /**
     * The stored resource of that name in this account, carrying its own ARN and path. Empty when
     * the account holds none, or when the stored record has no ARN to read: the operation fails as
     * {@code NoSuchEntity} either way, and minting an ARN for a resource that is not there would
     * only offer a policy something to match.
     */
    private Optional<StoredEntity> storedIamEntity(String resourceType, String name,
                                                   String accountId) {
        if (iamService == null) {
            return Optional.empty();
        }
        IamService iam = iamService.get();
        Optional<StoredEntity> stored = switch (resourceType) {
            case "user" -> iam.findUser(accountId, name)
                    .map(user -> new StoredEntity(user.getArn(), user.getPath()));
            case "role" -> iam.findRole(accountId, name)
                    .map(role -> new StoredEntity(role.getArn(), role.getPath()));
            case "group" -> iam.findGroup(accountId, name)
                    .map(group -> new StoredEntity(group.getArn(), group.getPath()));
            case "instance-profile" -> iam.findInstanceProfile(accountId, name)
                    .map(profile -> new StoredEntity(profile.getArn(), profile.getPath()));
            case "server-certificate" -> iam.findServerCertificate(name)
                    .map(certificate -> new StoredEntity(certificate.getArn(),
                            certificate.getPath()));
            default -> Optional.empty();
        };
        return stored.filter(entity -> entity.arn() != null && !entity.arn().isBlank());
    }

    // ── S3 ──────────────────────────────────────────────────────────────────────
    // Minted in the request's partition; IamEnforcementFilter re-mints an existing bucket's ARN
    // in the bucket's own partition through S3ResourcePolicyProvider before any policy sees it.
    private String buildS3Arn(String path, String region) {
        // path: /bucket or /bucket/key
        String partition = AwsRegions.partitionFor(region);
        String stripped = path.startsWith("/") ? path.substring(1) : path;
        if (stripped.isEmpty()) {
            return AwsArnUtils.Arn.global(partition, "s3", "", "*").toString();
        }
        // S3VirtualHostFilter rewrites a bucket-level virtual-hosted request (GET /) to
        // /bucket/, and the empty key after that separator is not part of the resource.
        // A policy names the bucket as arn:<partition>:s3:::bucket, so the trailing slash has to
        // go or a bucket-level ARN never matches. A key that itself ends in a slash
        // (a folder marker such as folder/) keeps it, because there the slash is key data.
        int firstSlash = stripped.indexOf('/');
        if (firstSlash == stripped.length() - 1) {
            stripped = stripped.substring(0, firstSlash);
        }
        return AwsArnUtils.Arn.global(partition, "s3", "", stripped).toString();
    }

    // ── Lambda ──────────────────────────────────────────────────────────────────

    /**
     * The function a Lambda request acts on, as an IAM policy names it. An invoke runs the function
     * in the account and Region its full or partial ARN names, so it is evaluated against that
     * function, and against the version or alias it names in the function name or in
     * {@code Qualifier}, since Lambda authorizes a qualified call against the qualified ARN (Lambda
     * Developer Guide, "Referencing functions in the Resource section of policies"). The reads
     * {@link #readsInTheAccountItNames} names act in the account a qualified ARN names too. Every
     * other route acts on the caller's own function of that name, whatever account an ARN names.
     * Those are evaluated unqualified, because not every one of them acts on the qualifier it is
     * given.
     */
    private String buildLambdaArn(String path, ContainerRequestContext ctx, String region, String accountId) {
        String webFunctionArn = webFunctionArn(path, region, accountId);
        if (webFunctionArn != null) {
            return webFunctionArn;
        }
        String microvmArn = microvmArn(path, ctx, region, accountId);
        if (microvmArn != null) {
            return microvmArn;
        }
        // TagResource, ListTags and UntagResource name the tagged function, image or other resource by its ARN.
        if (path.startsWith("/2017-03-31/tags/")) {
            return lambdaTaggedArn(path.substring("/2017-03-31/tags/".length()), accountId);
        }
        String executionArn = durableExecutionArn(path);
        if (executionArn == null) {
            executionArn = durableCallbackExecutionArn(path);
        }
        if (executionArn != null) {
            return executionArn;
        }
        String childArn = lambdaChildArn(path, ctx, region, accountId);
        if (childArn != null) {
            return childArn;
        }
        // path: /2015-03-31/functions/name or similar
        String name = extractSegmentAfter(path, "functions");
        if (name == null) {
            return createdFunctionArn(path, ctx, region, accountId);
        }
        boolean qualifiedTarget = path.endsWith("/functions/" + name + "/invocations")
                || path.endsWith("/functions/" + name + "/response-streaming-invocations")
                || path.endsWith("/functions/" + name + "/provisioned-concurrency");
        try {
            if (!qualifiedTarget) {
                LambdaArnUtils.ResolvedFunctionRef ref = LambdaArnUtils.resolve(name);
                boolean namedAccount = readsInTheAccountItNames(path, name, ref, ctx);
                return AwsArnUtils.Arn.of("lambda",
                        namedAccount ? ref.region() : region,
                        namedAccount ? ref.account() : accountId,
                        "function:" + ref.name()).toString();
            }
            LambdaArnUtils.ResolvedFunctionRef ref = LambdaArnUtils.resolveWithQualifier(
                    name, ctx.getUriInfo().getQueryParameters().getFirst("Qualifier"));
            String resource = ref.qualifier() != null
                    ? "function:" + ref.name() + ":" + ref.qualifier()
                    : "function:" + ref.name();
            return AwsArnUtils.Arn.of("lambda",
                    ref.region() != null ? ref.region() : region,
                    ref.account() != null ? ref.account() : accountId,
                    resource).toString();
        } catch (AwsException e) {
            // A reference Lambda refuses, which its handler answers with a 400 before acting on anything.
            return AwsArnUtils.Arn.of("lambda", region, accountId, "function:" + name).toString();
        }
    }

    /**
     * The function {@code CreateFunction} makes, which its body names, in the caller's account and Region, as
     * every route but an invoke acts there: the Service Reference gives it the {@code function} resource, so
     * its own action, and the tagging action its tags need, are checked on that function. {@code *} for any
     * other route that names no function.
     */
    private String createdFunctionArn(String path, ContainerRequestContext ctx, String region, String accountId) {
        if (!"POST".equalsIgnoreCase(ctx.getMethod()) || !"/2015-03-31/functions".equals(path)) {
            return "*";
        }
        JsonNode body = readJsonBody(ctx);
        JsonNode name = body == null ? null : body.get("FunctionName");
        if (name == null || !name.isTextual() || name.asText().isEmpty()) {
            return "*";
        }
        try {
            return AwsArnUtils.Arn.of("lambda", region, accountId,
                    "function:" + LambdaArnUtils.resolve(name.asText()).name()).toString();
        } catch (AwsException e) {
            // A name Lambda refuses, which its handler answers with a 400 before creating anything.
            return AwsArnUtils.Arn.of("lambda", region, accountId, "function:" + name.asText()).toString();
        }
    }

    /**
     * The resource of a Lambda event source mapping, code signing config or layer route, as the Service
     * Reference gives it. A mapping is looked up by its UUID, a config by the id that ends its ARN and a
     * layer version by its layer's name as given, an ARN included, and its number, each in the request's
     * account and Region, so that is the resource checked. A layer route's path is split as the router
     * splits it, before decoding, so an encoded {@code /} stays inside the layer's name, and its version
     * number is read as Lambda parses it, so {@code 01} is version 1. {@code PublishLayerVersion} is checked
     * against the layer and {@code GetLayerVersionByArn} against the version its {@code Arn} names, its
     * number read the same way. Listing configs, layers or a layer's versions, and creating a config, name
     * no resource, so they are {@code *}. Null for every other Lambda route.
     */
    private static String lambdaChildArn(String path, ContainerRequestContext ctx, String region, String accountId) {
        String mapping = segmentAfterPrefix(path, "/2015-03-31/event-source-mappings/");
        if (mapping != null) {
            return AwsArnUtils.Arn.of("lambda", region, accountId, "event-source-mapping:" + mapping).toString();
        }
        if (path.startsWith("/2020-04-22/code-signing-configs")) {
            String config = segmentAfterPrefix(path, "/2020-04-22/code-signing-configs/");
            return config == null ? "*"
                    : AwsArnUtils.Arn.of("lambda", region, accountId,
                            "code-signing-config:" + trailingName(config)).toString();
        }
        if (path.equals("/2018-10-31/layers")) {
            String arn = ctx.getUriInfo().getQueryParameters().getFirst("Arn");
            boolean byArn = "LayerVersion".equals(ctx.getUriInfo().getQueryParameters().getFirst("find"));
            return byArn && AwsArnUtils.isArnFor(arn, "lambda") ? layerVersionArn(arn) : "*";
        }
        String layers = "/2018-10-31/layers/";
        if (!path.startsWith(layers)) {
            return null;
        }
        String raw = IamActionRegistry.rawPath(ctx.getUriInfo());
        if (!raw.startsWith(layers)) {
            return "*";
        }
        String[] segments = raw.substring(layers.length()).split("/");
        if (segments.length < 2 || !"versions".equals(segments[1])) {
            return "*";
        }
        String layer = "layer:" + pathSegment(segments[0]);
        if (segments.length == 2) {
            return "POST".equalsIgnoreCase(ctx.getMethod())
                    ? AwsArnUtils.Arn.of("lambda", region, accountId, layer).toString() : "*";
        }
        return AwsArnUtils.Arn.of("lambda", region, accountId,
                layer + ":" + layerVersion(pathSegment(segments[2]))).toString();
    }

    /** A layer version ARN, its version number written as Lambda parses it. */
    private static String layerVersionArn(String arn) {
        int colon = arn.lastIndexOf(':');
        return arn.substring(0, colon + 1) + layerVersion(arn.substring(colon + 1));
    }

    /** A layer version number as Lambda parses it, so {@code 01} is 1; as given when it is no number. */
    private static String layerVersion(String version) {
        try {
            return Long.toString(Long.parseLong(version));
        } catch (NumberFormatException e) {
            return version;
        }
    }

    /**
     * A path segment as sent, decoded as a path parameter is: a {@code +} stays a {@code +}. A malformed
     * escape is kept as sent.
     */
    private static String pathSegment(String raw) {
        try {
            return URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return raw;
        }
    }

    /**
     * The resource of a Lambda MicroVM or network connector route, as the Service Reference gives it:
     * {@code microvm-image:<name>} for an action on an image, its versions and builds, and
     * {@code network-connector:<id>} for one on a connector. An identifier given as an ARN is taken for
     * its trailing name, in the request's account and Region, since that is the resource the service
     * looks up and acts on, whatever account or Region the ARN names. {@code RunMicrovm} is checked
     * against the image its {@code imageIdentifier} names, read the same way, an action on a running
     * MicroVM against the image that MicroVM runs, and a managed image's versions against the managed
     * image. Creating an image or a connector, listing them and listing MicroVMs name no resource, so
     * they are {@code *}, as is a MicroVM the account does not hold. Null for every other Lambda route.
     */
    private String microvmArn(String path, ContainerRequestContext ctx, String region, String accountId) {
        if (path.startsWith("/2025-09-09/")) {
            String image = segmentAfterPrefix(path, "/2025-09-09/microvm-images/");
            if (image != null) {
                return microvmImageArn(region, accountId, image);
            }
            String managed = segmentAfterPrefix(path, "/2025-09-09/managed-microvm-images/");
            if (managed != null) {
                return LambdaMicrovmsService.namesManagedImage(managed)
                        ? LambdaMicrovmsService.managedImageArn(region) : "*";
            }
            String microvm = segmentAfterPrefix(path, "/2025-09-09/microvms/");
            if (microvm != null) {
                return runningMicrovmImageArn(region, microvm);
            }
            if (path.equals("/2025-09-09/microvms") && "POST".equalsIgnoreCase(ctx.getMethod())) {
                JsonNode body = readJsonBody(ctx);
                String identifier = body == null ? null : body.path("imageIdentifier").asText(null);
                return identifier == null || identifier.isBlank() ? "*" : microvmImageArn(region, accountId, identifier);
            }
            return "*";
        }
        if (path.startsWith("/2026-04-04/")) {
            String connector = segmentAfterPrefix(path, "/2026-04-04/network-connectors/");
            return connector == null ? "*"
                    : AwsArnUtils.Arn.of("lambda", region, accountId,
                            "network-connector:" + trailingName(connector)).toString();
        }
        return null;
    }

    private static String microvmImageArn(String region, String accountId, String identifier) {
        return AwsArnUtils.Arn.of("lambda", region, accountId, "microvm-image:" + trailingName(identifier)).toString();
    }

    /** The image the caller's MicroVM runs, or {@code *} when the account holds no such MicroVM to act on. */
    private String runningMicrovmImageArn(String region, String microvmId) {
        if (microvmsService == null) {
            return "*";
        }
        return microvmsService.get().findMicrovm(region, microvmId)
                .map(vm -> vm.imageArn)
                .filter(arn -> arn != null && !arn.isBlank())
                .orElse("*");
    }

    /** An identifier's name: an ARN's part after its last {@code :}, as the MicroVM service unwraps it. */
    private static String trailingName(String identifier) {
        return identifier.startsWith("arn:") ? identifier.substring(identifier.lastIndexOf(':') + 1) : identifier;
    }

    /**
     * The resource a Lambda tag route acts on, from the ARN it names. Tagging looks a function up by the
     * ARN's Region and name, and a MicroVM image or network connector by the ARN's Region and trailing
     * name, in the caller's own account whatever account the ARN names, so that resource is the one
     * checked. The MicroVM tag handler takes any target holding {@code :microvm-image:} or
     * {@code :network-connector:}, an {@code arn:} prefix or not, so the target is read as it reads it.
     * Any other ARN, an event source mapping's included, has to name its resource exactly or is
     * refused, so it is checked as written.
     */
    private static String lambdaTaggedArn(String tagged, String accountId) {
        if (LambdaMicrovmsService.ownsArn(tagged)) {
            // Read as LambdaTagController and LambdaMicrovmsService read it: the Region is the fourth field,
            // the name what follows the last colon.
            String[] fields = tagged.split(":");
            String region = fields.length > 3 ? fields[3] : "";
            String type = tagged.contains(":microvm-image:") ? "microvm-image:"
                    : tagged.contains(":network-connector:") ? "network-connector:" : null;
            if (type != null && !region.isBlank()) {
                return AwsArnUtils.Arn.of("lambda", region, accountId,
                        type + tagged.substring(tagged.lastIndexOf(':') + 1)).toString();
            }
        }
        if (!tagged.startsWith("arn:")) {
            return "*";
        }
        try {
            LambdaArnUtils.ResolvedFunctionRef ref = LambdaArnUtils.resolve(tagged);
            return ref.qualifier() != null ? tagged
                    : AwsArnUtils.Arn.of("lambda", ref.region(), accountId, "function:" + ref.name()).toString();
        } catch (AwsException e) {
            // Not a function ARN Lambda takes, which it tags only when the ARN names the resource exactly.
            return tagged;
        }
    }

    /**
     * The resource of an EventBridge Scheduler route: {@code schedule/<group>/<name>}, the group read where
     * Scheduler reads it, the body's {@code GroupName} on a create or an update and {@code groupName}
     * otherwise, and {@code default} when the request names none; {@code schedule-group/<name>}; or the
     * ARN a tagging route names. The list routes name no resource.
     */
    private String buildSchedulerArn(String path, ContainerRequestContext ctx, String region, String accountId,
                                     String partition) {
        String schedule = segmentAfterPrefix(path, "/schedules/");
        if (schedule != null) {
            String group;
            if ("POST".equalsIgnoreCase(ctx.getMethod()) || "PUT".equalsIgnoreCase(ctx.getMethod())) {
                JsonNode body = readJsonBody(ctx);
                group = body == null ? null : body.path("GroupName").asText(null);
            } else {
                group = ctx.getUriInfo().getQueryParameters().getFirst("groupName");
            }
            if (group == null || group.isBlank()) {
                group = "default";
            }
            return new AwsArnUtils.Arn(partition, "scheduler", region, accountId,
                    "schedule/" + group + "/" + schedule).toString();
        }
        String scheduleGroup = segmentAfterPrefix(path, "/schedule-groups/");
        if (scheduleGroup != null) {
            return new AwsArnUtils.Arn(partition, "scheduler", region, accountId,
                    "schedule-group/" + scheduleGroup).toString();
        }
        return taggedResource(ctx);
    }

    /**
     * The resource of an Aurora DSQL route, as the Service Authorization Reference names it: the cluster
     * a cluster route names, the stream a stream route names (its cluster, to create or list streams), or
     * the ARN a tagging route names. CreateCluster and ListClusters are checked against
     * {@code cluster/*}, as DSQL checks them (measured).
     */
    private String buildDsqlArn(String path, ContainerRequestContext ctx, String region, String accountId,
                                String partition) {
        String cluster = "/cluster".equals(path) ? "*" : segmentAfterPrefix(path, "/cluster/");
        if (cluster == null) {
            cluster = segmentAfterPrefix(path, "/clusters/");
        }
        String streamCluster = segmentAfterPrefix(path, "/stream/");
        if (streamCluster != null) {
            String stream = segmentAfterPrefix(path, "/stream/" + streamCluster + "/");
            cluster = stream == null ? streamCluster : streamCluster + "/stream/" + stream;
        }
        if (cluster != null) {
            return new AwsArnUtils.Arn(partition, "dsql", region, accountId, "cluster/" + cluster).toString();
        }
        return taggedResource(ctx);
    }

    /** The resource of an EventBridge Pipes route: {@code pipe/<name>}, or the ARN a tagging route names. */
    private String buildPipesArn(String path, ContainerRequestContext ctx, String region, String accountId,
                                 String partition) {
        String pipe = segmentAfterPrefix(path, "/v1/pipes/");
        if (pipe != null) {
            return new AwsArnUtils.Arn(partition, "pipes", region, accountId, "pipe/" + pipe).toString();
        }
        return taggedResource(ctx);
    }

    /**
     * The resource of an EKS route, as the Service Reference gives it, in the caller's account, where EKS
     * looks everything up. A cluster is the ARN EKS holds it under, or for one it holds none of, the ARN a
     * create mints in the request's Region; {@code CreateCluster} is checked against the cluster its body
     * names, as the EKS user guide's example policy for creating a cluster grants it. A node group, Fargate
     * profile, add-on, access entry or Pod Identity association is the ARN EKS holds it under, which ends
     * in an id the request does not carry, or {@code *} when EKS holds none to act on; listing or creating
     * them, and every other route under a cluster, act on the cluster. The path is split as the router
     * splits it, before decoding, so an encoded {@code /} stays inside a name, and an access entry's
     * principal is the rest of the path, as EKS takes it. {@code DescribeUpdate} acts on the add-on its
     * {@code addonName} names, since the updates EKS keeps are its add-ons', and otherwise on the cluster. A
     * tagging route acts on the caller's cluster named by what follows the ARN's last {@code /}, as EKS tags
     * it, whatever the ARN names before that. Deregistering a cluster acts on that cluster, and an EKS
     * Anywhere subscription route on the subscription its id names. Listing clusters, registering one, the
     * account-wide reads, and an identity provider config or capability, which end in an id Floci does not
     * keep, name no resource, so they are {@code *}.
     */
    private String buildEksArn(String path, ContainerRequestContext ctx, String region, String accountId) {
        if (path.equals("/tags") || path.startsWith("/tags/")) {
            String tagged = taggedArn(ctx);
            int slash = tagged == null ? -1 : tagged.lastIndexOf('/');
            return slash < 0 || slash == tagged.length() - 1 ? "*"
                    : eksClusterArn(tagged.substring(slash + 1), region, accountId);
        }
        String[] segments = IamActionRegistry.routedPath(ctx.getUriInfo()).substring(1).split("/", -1);
        if (segments.length == 1) {
            JsonNode body = "clusters".equals(segments[0]) && "POST".equalsIgnoreCase(ctx.getMethod())
                    ? readJsonBody(ctx) : null;
            String cluster = body == null ? null : body.path("name").asText(null);
            return cluster == null || cluster.isBlank() ? "*" : eksClusterArn(cluster, region, accountId);
        }
        String name = pathSegment(segments[1]);
        if (name.isEmpty()) {
            return "*";
        }
        return switch (segments[0]) {
            case "clusters" -> eksClusterRouteArn(name, segments, ctx.getMethod(), region, accountId);
            case "cluster-registrations" -> eksClusterArn(name, region, accountId);
            case "eks-anywhere-subscriptions" ->
                    AwsArnUtils.Arn.of("eks", region, accountId, "eks-anywhere-subscription/" + name).toString();
            default -> "*";
        };
    }

    /** The resource of a {@code method} route under the cluster {@code cluster}, its path split into {@code segments}. */
    private String eksClusterRouteArn(String cluster, String[] segments, String method, String region,
                                      String accountId) {
        if (segments.length < 4 || segments[3].isEmpty()) {
            return eksClusterArn(cluster, region, accountId);
        }
        String collection = segments[2];
        return switch (collection) {
            case "node-groups", "fargate-profiles", "addons", "pod-identity-associations" ->
                    eksHeldArn(cluster, collection, pathSegment(segments[3]));
            case "access-entries" -> eksHeldArn(cluster, collection,
                    pathSegment(String.join("/", List.of(segments).subList(3, accessEntryEnd(segments, method)))));
            case "updates" -> {
                // The updates Floci keeps are its add-ons', and EKS returns one whether or not the request names
                // its add-on, so the update is checked against the add-on it was made to.
                Optional<String> addon = segments.length == 4 && eksService != null
                        ? eksService.get().findResourceArn(cluster, collection, pathSegment(segments[3]))
                        : Optional.empty();
                yield addon.orElseGet(() -> eksClusterArn(cluster, region, accountId));
            }
            case "identity-provider-configs" ->
                    "associate".equals(segments[3]) ? eksClusterArn(cluster, region, accountId) : "*";
            case "capabilities" -> "*";
            default -> eksClusterArn(cluster, region, accountId);
        };
    }

    /**
     * Where an access entry's principal ends in {@code segments}: before {@code access-policies} on the routes
     * that act on the entry's policies, as the router reads them (a DELETE only when a cluster access policy
     * ARN follows), and at the end on every other route.
     */
    private static int accessEntryEnd(String[] segments, String method) {
        int last = segments.length - 1;
        if (("GET".equalsIgnoreCase(method) || "POST".equalsIgnoreCase(method))
                && last > 3 && "access-policies".equals(segments[last])) {
            return last;
        }
        if ("DELETE".equalsIgnoreCase(method)) {
            for (int i = last - 1; i > 3; i--) {
                if ("access-policies".equals(segments[i]) && ACCESS_POLICY_ARN.matcher(
                        String.join("/", List.of(segments).subList(i + 1, segments.length))).matches()) {
                    return i;
                }
            }
        }
        return segments.length;
    }

    /** The ARN EKS holds the cluster {@code name} under, or else the one a create mints in {@code region}. */
    private String eksClusterArn(String name, String region, String accountId) {
        Optional<String> held = eksService == null ? Optional.empty()
                : eksService.get().findResourceArn(name, null, null);
        return held.orElseGet(() -> AwsArnUtils.Arn.of("eks", region, accountId, "cluster/" + name).toString());
    }

    /** The ARN EKS holds {@code name} of {@code collection} in the cluster under, or {@code *} when it holds none. */
    private String eksHeldArn(String cluster, String collection, String name) {
        return eksService == null ? "*" : eksService.get().findResourceArn(cluster, collection, name).orElse("*");
    }

    /** The ARN a shared tagging route names, or {@code *} for any other route. */
    private String taggedResource(ContainerRequestContext ctx) {
        String arn = taggedArn(ctx);
        return arn != null && arn.startsWith("arn:") ? arn : "*";
    }

    /**
     * The ARN a shared tagging route acts on, read where its controller reads it: after {@code /tags/} or
     * {@code /v1/tags/} in the path; on {@code POST /tags}, the body's {@code resourceArn}; and on the other
     * bare routes, the {@code resourceArn} query parameter. Null for any other route, or when the request
     * names none.
     */
    public String taggedArn(ContainerRequestContext ctx) {
        String path = ctx.getUriInfo().getPath();
        for (String route : List.of("/tags", "/v1/tags")) {
            if (path.startsWith(route + "/")) {
                return path.substring(route.length() + 1);
            }
        }
        if ("/tags".equals(path) && "POST".equalsIgnoreCase(ctx.getMethod())) {
            JsonNode body = readJsonBody(ctx);
            return body == null ? null : body.path("resourceArn").asText(null);
        }
        if ("/tags".equals(path) || "/v1/tags".equals(path)) {
            return ctx.getUriInfo().getQueryParameters().getFirst("resourceArn");
        }
        return null;
    }

    /** The path segment right after {@code prefix}, or null when the path doesn't start with it or names none. */
    private static String segmentAfterPrefix(String path, String prefix) {
        if (!path.startsWith(prefix)) {
            return null;
        }
        String rest = path.substring(prefix.length());
        int slash = rest.indexOf('/');
        String segment = slash < 0 ? rest : rest.substring(0, slash);
        return segment.isEmpty() ? null : segment;
    }

    /**
     * The resource of a Lambda Web Functions route. An action on a revision or an endpoint is evaluated
     * against that child's ARN; an action on the function, a create of a revision or endpoint, the list of
     * either, and the tag and resource-policy routes against the function's (Lambda Developer Guide,
     * "Lambda Web Function permissions"). ListWebFunctions, GetWebAccountSettings and CreateWebFunction
     * name no resource. Null for every other Lambda route.
     */
    private static String webFunctionArn(String path, String region, String accountId) {
        if (!path.startsWith("/2025-03-07/")) {
            return null;
        }
        String rest = path.substring("/2025-03-07/".length());
        if (rest.startsWith("tags/") || rest.startsWith("resource-policy/")) {
            String arn = rest.substring(rest.indexOf('/') + 1);
            return arn.startsWith("arn:") ? arn : "*";
        }
        if (!rest.startsWith("web-functions/")) {
            return "*";
        }
        List<String> segments = new ArrayList<>(List.of(rest.substring("web-functions/".length()).split("/")));
        if (segments.isEmpty() || segments.getFirst().isEmpty()) {
            return "*";
        }
        String functionArn;
        if (segments.getFirst().startsWith("arn:") && segments.size() > 1) {
            // A function ARN carries one slash: arn:...:web-function/<name>.
            functionArn = segments.get(0) + "/" + segments.get(1);
            segments.subList(0, 2).clear();
        } else {
            functionArn = AwsArnUtils.Arn.of("lambda", region, accountId,
                    "web-function/" + segments.getFirst()).toString();
            segments.removeFirst();
        }
        if (segments.size() >= 2 && ("revisions".equals(segments.get(0)) || "endpoints".equals(segments.get(0)))) {
            String child = "revisions".equals(segments.get(0)) ? "revision" : "endpoint";
            String id = String.join("/", segments.subList(1, segments.size()));
            if (id.startsWith("arn:") && id.contains("/" + child + "/")) {
                // A revision or endpoint ARN ends in its id: arn:...:web-function/<name>/revision/<id>.
                id = id.substring(id.lastIndexOf('/') + 1);
            }
            return functionArn + "/" + child + "/" + id;
        }
        return functionArn;
    }

    /**
     * {@code GetFunction}, {@code GetFunctionConfiguration} and {@code ListDurableExecutionsByFunction}
     * read a function named by a qualified full ARN in the account the ARN names, where the version or
     * alias is resolved ({@code LambdaService.getFunction(region, name, qualifier)}). Unqualified, or
     * by partial ARN, they read the caller's own function of that name, as every other route acts.
     */
    private static boolean readsInTheAccountItNames(String path, String name,
                                                    LambdaArnUtils.ResolvedFunctionRef ref,
                                                    ContainerRequestContext ctx) {
        if (!"GET".equals(ctx.getMethod()) || !name.startsWith("arn:")) {
            return false;
        }
        String route = path.substring(path.indexOf("/functions/" + name) + "/functions/".length() + name.length());
        if (!route.isEmpty() && !route.equals("/configuration") && !route.equals("/durable-executions")) {
            return false;
        }
        String qualifier = ctx.getUriInfo().getQueryParameters().getFirst("Qualifier");
        return ref.qualifier() != null || (qualifier != null && !qualifier.isBlank());
    }

    /** Durable execution actions are authorized against the execution ARN, which carries the version. */
    private static String durableExecutionArn(String path) {
        String prefix = "/durable-executions/";
        int executions = path.indexOf(prefix);
        if (executions < 0) {
            return null;
        }
        Matcher matcher = LambdaArnUtils.DURABLE_EXECUTION_ARN.matcher(path.substring(executions + prefix.length()));
        return matcher.lookingAt() ? matcher.group() : null;
    }

    /** A callback id carries its execution ARN, so the callback actions are authorized against that execution. */
    private static String durableCallbackExecutionArn(String path) {
        String prefix = "/durable-execution-callbacks/";
        int callbacks = path.indexOf(prefix);
        int action = path.lastIndexOf('/');
        if (callbacks < 0 || action <= callbacks + prefix.length()) {
            return null;
        }
        return DurableTokens.callbackExecutionArn(path.substring(callbacks + prefix.length(), action))
                .filter(arn -> LambdaArnUtils.DURABLE_EXECUTION_ARN.matcher(arn).matches())
                .orElse(null);
    }

    // ── SQS ─────────────────────────────────────────────────────────────────────
    private String buildSqsArn(ContainerRequestContext ctx, String region, String accountId) {
        String queueUrl = firstFormParam(ctx, "QueueUrl");
        if (queueUrl == null) {
            JsonNode json = readJsonBody(ctx);
            if (json != null && json.isObject()) {
                if (json.hasNonNull("QueueUrl")) {
                    queueUrl = json.get("QueueUrl").asText().trim();
                } else if (json.hasNonNull("QueueName")) {
                    String queueName = json.get("QueueName").asText().trim();
                    if (!queueName.isEmpty()) {
                        return AwsArnUtils.Arn.of("sqs", region, accountId, queueName).toString();
                    }
                }
            }
        }
        if (queueUrl != null && !queueUrl.isEmpty()) {
            String queueName = queueUrl.substring(queueUrl.lastIndexOf('/') + 1);
            return AwsArnUtils.Arn.of("sqs", region, accountId, queueName).toString();
        }
        return AwsArnUtils.Arn.of("sqs", region, accountId, "*").toString();
    }

    // ── SNS ─────────────────────────────────────────────────────────────────────
    private String buildSnsArn(ContainerRequestContext ctx, String region, String accountId) {
        String topicArn = firstFormParam(ctx, "TopicArn");
        if (topicArn == null) {
            JsonNode json = readJsonBody(ctx);
            if (json != null && json.isObject() && json.hasNonNull("TopicArn")) {
                topicArn = json.get("TopicArn").asText().trim();
            }
        }
        return (topicArn != null && !topicArn.isEmpty())
                ? topicArn
                : AwsArnUtils.Arn.of("sns", region, accountId, "*").toString();
    }

    // ── DynamoDB ─────────────────────────────────────────────────────────────────
    private String buildDynamoDbArn(ContainerRequestContext ctx, String region, String accountId) {
        List<String> arns = buildDynamoDbArns(ctx, region, accountId);
        return arns.isEmpty() ? "*" : arns.getFirst();
    }

    private List<String> buildDynamoDbArns(ContainerRequestContext ctx, String region, String accountId) {
        JsonNode json = readJsonBody(ctx);
        if (json != null && json.isObject()) {
            if (json.hasNonNull("TableName")) {
                String tableName = json.get("TableName").asText().trim();
                if (!tableName.isEmpty()) {
                    return List.of(toDynamoDbTableArn(tableName, region, accountId));
                }
            }
            if (json.path("TableCreationParameters").hasNonNull("TableName")) {
                String tableName = json.path("TableCreationParameters").get("TableName").asText().trim();
                if (!tableName.isEmpty()) {
                    return List.of(toDynamoDbTableArn(tableName, region, accountId));
                }
            }
            if (json.hasNonNull("ResourceArn")) {
                String resourceArn = json.get("ResourceArn").asText().trim();
                if (!resourceArn.isEmpty()) {
                    return List.of(resourceArn);
                }
            }
            if (json.hasNonNull("TableArn")) {
                String tableArn = json.get("TableArn").asText().trim();
                if (!tableArn.isEmpty()) {
                    return List.of(tableArn);
                }
            }
            if (json.hasNonNull("StreamArn")) {
                String streamArn = json.get("StreamArn").asText().trim();
                if (!streamArn.isEmpty()) {
                    return List.of(streamArn);
                }
            }
            if (json.hasNonNull("ExportArn")) {
                String exportArn = json.get("ExportArn").asText().trim();
                if (!exportArn.isEmpty()) {
                    return List.of(exportArn);
                }
            }
            if (json.hasNonNull("ImportArn")) {
                String importArn = json.get("ImportArn").asText().trim();
                if (!importArn.isEmpty()) {
                    return List.of(importArn);
                }
            }
            // AWS authorizes RestoreTableToPointInTime on its source table.
            if (json.hasNonNull("SourceTableArn")) {
                String sourceTableArn = json.get("SourceTableArn").asText().trim();
                if (!sourceTableArn.isEmpty()) {
                    return List.of(sourceTableArn);
                }
            }
            if (json.hasNonNull("SourceTableName")) {
                String sourceTableName = json.get("SourceTableName").asText().trim();
                if (!sourceTableName.isEmpty()) {
                    return List.of(toDynamoDbTableArn(sourceTableName, region, accountId));
                }
            }
            if (json.hasNonNull("RequestItems") && json.get("RequestItems").isObject()) {
                Set<String> arns = new LinkedHashSet<>();
                Iterator<String> fieldNames = json.get("RequestItems").fieldNames();
                while (fieldNames.hasNext()) {
                    String table = fieldNames.next().trim();
                    if (!table.isEmpty()) {
                        arns.add(toDynamoDbTableArn(table, region, accountId));
                    }
                }
                if (!arns.isEmpty()) {
                    return new ArrayList<>(arns);
                }
            }
            if (json.hasNonNull("TransactItems") && json.get("TransactItems").isArray()) {
                Set<String> arns = new LinkedHashSet<>();
                JsonNode items = json.get("TransactItems");
                for (JsonNode item : items) {
                    String t = null;
                    if (item.hasNonNull("Put") && item.get("Put").hasNonNull("TableName")) {
                        t = item.get("Put").get("TableName").asText();
                    } else if (item.hasNonNull("Delete") && item.get("Delete").hasNonNull("TableName")) {
                        t = item.get("Delete").get("TableName").asText();
                    } else if (item.hasNonNull("Update") && item.get("Update").hasNonNull("TableName")) {
                        t = item.get("Update").get("TableName").asText();
                    } else if (item.hasNonNull("ConditionCheck") && item.get("ConditionCheck").hasNonNull("TableName")) {
                        t = item.get("ConditionCheck").get("TableName").asText();
                    } else if (item.hasNonNull("Get") && item.get("Get").hasNonNull("TableName")) {
                        t = item.get("Get").get("TableName").asText();
                    }
                    if (t != null) {
                        t = t.trim();
                        if (!t.isEmpty()) {
                            arns.add(toDynamoDbTableArn(t, region, accountId));
                        }
                    }
                }
                if (!arns.isEmpty()) {
                    return new ArrayList<>(arns);
                }
            }
            if (json.hasNonNull("Statement")) {
                String stmt = json.get("Statement").asText().trim();
                String table = extractDynamoDbTableFromPartiQL(stmt);
                if (table != null && !table.isEmpty()) {
                    return List.of(toDynamoDbTableArn(table, region, accountId));
                }
            }
            if (json.hasNonNull("Statements") && json.get("Statements").isArray()) {
                Set<String> arns = new LinkedHashSet<>();
                for (JsonNode s : json.get("Statements")) {
                    if (s.hasNonNull("Statement")) {
                        String table = extractDynamoDbTableFromPartiQL(s.get("Statement").asText());
                        if (table != null && !table.isEmpty()) {
                            arns.add(toDynamoDbTableArn(table, region, accountId));
                        }
                    }
                }
                if (!arns.isEmpty()) {
                    return new ArrayList<>(arns);
                }
            }
            if (json.hasNonNull("TransactStatements") && json.get("TransactStatements").isArray()) {
                Set<String> arns = new LinkedHashSet<>();
                for (JsonNode s : json.get("TransactStatements")) {
                    if (s.hasNonNull("Statement")) {
                        String table = extractDynamoDbTableFromPartiQL(s.get("Statement").asText());
                        if (table != null && !table.isEmpty()) {
                            arns.add(toDynamoDbTableArn(table, region, accountId));
                        }
                    }
                }
                if (!arns.isEmpty()) {
                    return new ArrayList<>(arns);
                }
            }
        }
        return List.of("*");
    }

    private static String extractDynamoDbTableFromPartiQL(String statement) {
        return io.github.hectorvent.floci.services.dynamodb.DynamoDbPartiQLParser.extractTable(statement);
    }

    public static String toDynamoDbTableArn(String tableName, String region, String accountId) {
        if (AwsArnUtils.isArnFor(tableName, "dynamodb")) {
            return tableName;
        }
        return AwsArnUtils.Arn.of("dynamodb", region, accountId, "table/" + tableName).toString();
    }

    // ── Kinesis ──────────────────────────────────────────────────────────────────
    private String buildKinesisArn(ContainerRequestContext ctx, String region, String accountId) {
        JsonNode json = readJsonBody(ctx);
        if (json != null && json.isObject()) {
            if (json.hasNonNull("StreamARN")) {
                String streamArn = json.get("StreamARN").asText().trim();
                if (!streamArn.isEmpty()) {
                    return streamArn;
                }
            }
            if (json.hasNonNull("StreamName")) {
                String streamName = json.get("StreamName").asText().trim();
                if (!streamName.isEmpty()) {
                    if (AwsArnUtils.isArnFor(streamName, "kinesis")) {
                        return streamName;
                    }
                    return AwsArnUtils.Arn.of("kinesis", region, accountId, "stream/" + streamName).toString();
                }
            }
            if (json.hasNonNull("ResourceARN")) {
                String resourceArn = json.get("ResourceARN").asText().trim();
                if (!resourceArn.isEmpty()) {
                    return resourceArn;
                }
            }
        }
        return AwsArnUtils.Arn.of("kinesis", region, accountId, "stream/*").toString();
    }

    // ── Secrets Manager ──────────────────────────────────────────────────────────

    /**
     * Uses the serving operation, as {@link #buildLogResources} does: {@code CreateSecret}
     * names the secret in {@code Name} and every other operation in {@code SecretId}, so a field the
     * handler does not read cannot choose the resource. A minted ARN takes the request's partition,
     * the one the secret is created in, which a region Floci does not know cannot be mapped to.
     */
    public List<String> buildSecretsManagerResources(String action, ContainerRequestContext ctx,
                                                     String region, String accountId, String partition) {
        return List.of(buildSecretsManagerArn(action, ctx, region, accountId, partition));
    }

    /**
     * A secret named by its name or a partial ARN is authorized against its full ARN, random suffix
     * included, so a {@code secret:<name>-??????} policy matches it as it does on AWS. The suffix of
     * the secret {@code CreateSecret} is making is not assigned until it is created.
     */
    private String buildSecretsManagerArn(String action, ContainerRequestContext ctx, String region,
                                          String accountId, String partition) {
        JsonNode json = readJsonBody(ctx);
        if (json != null && json.isObject()) {
            if ("secretsmanager:CreateSecret".equals(action)) {
                if (json.hasNonNull("Name")) {
                    String name = json.get("Name").asText().trim();
                    if (!name.isEmpty()) {
                        return secretArn(name + UNASSIGNED_SECRET_SUFFIX, region, accountId, partition);
                    }
                }
            } else if (json.hasNonNull("SecretId")) {
                String secretId = json.get("SecretId").asText().trim();
                if (!secretId.isEmpty()) {
                    Optional<String> stored = storedSecretArn(secretId, region, accountId);
                    if (AwsArnUtils.isArnFor(secretId, "secretsmanager")) {
                        return stored.orElse(secretId);
                    }
                    return stored.orElseGet(() -> secretArn(secretId, region, accountId, partition));
                }
            }
        }
        return secretArn("*", region, accountId, partition);
    }

    private Optional<String> storedSecretArn(String secretId, String region, String accountId) {
        if (secretsManagerService == null) {
            return Optional.empty();
        }
        return secretsManagerService.get().findArn(accountId, secretId, region);
    }

    private static String secretArn(String name, String region, String accountId, String partition) {
        return new AwsArnUtils.Arn(partition, "secretsmanager", region, accountId, "secret:" + name).toString();
    }

    // ── SSM ──────────────────────────────────────────────────────────────────────
    private String buildSsmArn(ContainerRequestContext ctx, String region, String accountId) {
        JsonNode json = readJsonBody(ctx);
        if (json != null && json.isObject()) {
            if (json.hasNonNull("Name")) {
                String name = json.get("Name").asText().trim();
                if (!name.isEmpty()) {
                    if (AwsArnUtils.isArnFor(name, "ssm")) {
                        return name;
                    }
                    String paramResource = name.startsWith("/") ? "parameter" + name : "parameter/" + name;
                    return AwsArnUtils.Arn.of("ssm", region, accountId, paramResource).toString();
                }
            }
        }
        return AwsArnUtils.Arn.of("ssm", region, accountId, "parameter/*").toString();
    }

    // ── KMS ──────────────────────────────────────────────────────────────────────

    /**
     * The resources a KMS request is evaluated against, as the KMS permissions reference gives
     * them (Developer Guide, "AWS KMS permissions"). A key-scoped operation, which is every
     * crypto operation and every key-management one Floci dispatches, is evaluated against the
     * key its {@code KeyId} names, resolving an alias name or an alias ARN to the key it points
     * at exactly as the handler resolves it. {@code Decrypt} names its key in an optional
     * {@code KeyId}; without one the key is the one that encrypted the {@code CiphertextBlob},
     * read out of the blob the same way the handler reads it.
     *
     * <p>{@code CreateKey}, {@code GenerateRandom}, {@code ListKeys}, {@code ListAliases} and
     * {@code ListRetirableGrants} apply to no particular resource, so they stay at {@code *},
     * the element the reference itself requires a policy to name for them. The alias operations
     * name their resources the way the alias permissions page gives them: {@code CreateAlias}
     * the alias and the key, {@code UpdateAlias} the alias, the key it points at and the key it
     * will point at, and {@code DeleteAlias} the alias and the key it points at.
     *
     * <p>{@code ReEncrypt} is a stopgap here: AWS authorizes it as {@code kms:ReEncryptFrom} on
     * the source key and {@code kms:ReEncryptTo} on the destination, while this evaluates the
     * single {@code kms:ReEncrypt} action against both keys, so a policy naming either action
     * AWS publishes still binds to nothing. The source is the key its optional
     * {@code SourceKeyId} names and otherwise the key that encrypted the
     * {@code CiphertextBlob}, never a {@code KeyId} member, which the handler does not read on
     * {@code ReEncrypt}.
     *
     * <p>The grant operations name the key the grant they act on belongs to, the way the handler
     * resolves it: a {@code RetireGrant} carrying a {@code GrantToken} the key the grant it
     * names belongs to, a {@code RetireGrant} or a {@code RevokeGrant} identifying its grant by
     * {@code KeyId} and {@code GrantId} the key it names together with the key that grant
     * belongs to. They coincide for a request naming a grant on its own key; naming both closes
     * the pair that names one key while retiring or revoking another key's grant.
     *
     * <p>A request nothing resolves names every key the account holds, which the permissive
     * {@code key/*} pattern a policy may scope to still matches; the operation then fails in the
     * handler with its own {@code NotFoundException}.
     */
    public List<String> buildKmsResources(String action, ContainerRequestContext ctx,
                                           String region, String accountId, String partition) {
        JsonNode body = readJsonBody(ctx);
        if (body == null) {
            body = MissingNode.getInstance();
        }
        return switch (action) {
            case "kms:CreateKey", "kms:GenerateRandom", "kms:ListKeys", "kms:ListAliases",
                    "kms:ListRetirableGrants" -> List.of("*");
            case "kms:CreateAlias" -> createAliasTargets(body, region, accountId, partition);
            case "kms:UpdateAlias" -> updateAliasTargets(body, region, accountId, partition);
            case "kms:DeleteAlias" -> deleteAliasTargets(body, region, accountId, partition);
            case "kms:Decrypt" -> List.of(decryptTarget(body, region, accountId, partition));
            case "kms:ReEncrypt" -> reEncryptTargets(body, region, accountId, partition);
            case "kms:RetireGrant" -> retireGrantTargets(body, region, accountId, partition);
            case "kms:RevokeGrant" -> revokeGrantTargets(body, region, accountId, partition);
            default -> List.of(keyTarget(body, "KeyId", region, accountId, partition));
        };
    }

    /** The key a request names in {@code member}, or every key the account holds when it names none. */
    private String keyTarget(JsonNode body, String member, String region, String accountId,
                             String partition) {
        String keyId = body.path(member).asText(null);
        return keyId == null || keyId.isBlank()
                ? keyWildcard(region, accountId, partition)
                : keyArn(keyId, region, accountId, partition);
    }

    /**
     * The key ARN a request's identifier names: the stored key's own ARN when the account holds
     * it, in the partition it was created in; a key ARN the account holds nothing of as given,
     * which is the caller's own naming of the key the operation will refuse; and a bare key id
     * minted in the request's partition, the partition the create mints in. An alias that
     * resolves to nothing names every key, since the operation then fails before a policy
     * matters.
     */
    private String keyArn(String keyIdOrAliasOrArn, String region, String accountId, String partition) {
        if (kmsService != null) {
            Optional<String> stored = kmsService.get().findKeyArn(keyIdOrAliasOrArn, region);
            if (stored.isPresent()) {
                return stored.get();
            }
        }
        if (AwsArnUtils.isArnFor(keyIdOrAliasOrArn, "kms")) {
            return keyIdOrAliasOrArn;
        }
        if (keyIdOrAliasOrArn.startsWith("alias/") || keyIdOrAliasOrArn.contains(":alias/")) {
            return keyWildcard(region, accountId, partition);
        }
        return new AwsArnUtils.Arn(partition, "kms", region, accountId,
                "key/" + keyIdOrAliasOrArn).toString();
    }

    /**
     * The key a {@code Decrypt} names: the optional {@code KeyId} when the request carries one,
     * and otherwise the key that encrypted the {@code CiphertextBlob}, which is the key the
     * service decrypts with and answers.
     */
    private String decryptTarget(JsonNode body, String region, String accountId, String partition) {
        String keyId = body.path("KeyId").asText(null);
        if (keyId != null && !keyId.isBlank()) {
            return keyArn(keyId, region, accountId, partition);
        }
        return ciphertextKeyTarget(body, region, accountId, partition);
    }

    /**
     * The key that encrypted the request's {@code CiphertextBlob}, read out of the blob the
     * same way the handler reads it, or every key the account holds when the blob names none
     * the account holds.
     */
    private String ciphertextKeyTarget(JsonNode body, String region, String accountId, String partition) {
        JsonNode ciphertext = body.path("CiphertextBlob");
        if (kmsService != null && !ciphertext.isMissingNode() && !ciphertext.isNull()) {
            if (!ciphertext.isTextual() && !ciphertext.isBinary()) {
                return keyWildcard(region, accountId, partition);
            }
            byte[] blob;
            try {
                blob = ciphertext.binaryValue();
            } catch (IOException e) {
                return keyWildcard(region, accountId, partition);
            }
            String keyArn = blob == null ? null : kmsService.get().decryptToKeyArn(blob, region);
            if (keyArn != null) {
                return keyArn;
            }
        }
        return keyWildcard(region, accountId, partition);
    }

    /**
     * Both keys a {@code ReEncrypt} acts on: the source its optional {@code SourceKeyId} names
     * and otherwise the key that encrypted the {@code CiphertextBlob}, which is the key the
     * service decrypts with, and the destination its {@code DestinationKeyId} names. The
     * handler never reads a {@code KeyId} on {@code ReEncrypt}, so neither does this. The
     * filter authorizes a request once per resource, so the caller has to be allowed on both.
     */
    private List<String> reEncryptTargets(JsonNode body, String region, String accountId,
                                          String partition) {
        Set<String> keys = new LinkedHashSet<>();
        String sourceKeyId = body.path("SourceKeyId").asText(null);
        keys.add(sourceKeyId == null || sourceKeyId.isBlank()
                ? ciphertextKeyTarget(body, region, accountId, partition)
                : keyArn(sourceKeyId, region, accountId, partition));
        keys.add(keyTarget(body, "DestinationKeyId", region, accountId, partition));
        return new ArrayList<>(keys);
    }

    /**
     * The key a {@code RetireGrant} acts on, as the handler resolves it: the key the grant its
     * {@code GrantToken} names belongs to, since the handler finds the grant by the token
     * alone and only cross-verifies the other members against it, and for a request identifying
     * its grant by {@code KeyId} and {@code GrantId} the key it names together with the key
     * that grant belongs to, which the pair identifies for the handler. A token naming no
     * grant the account holds keeps the permissive fallback; the handler refuses the request
     * with its own {@code NotFoundException}.
     */
    private List<String> retireGrantTargets(JsonNode body, String region, String accountId,
                                             String partition) {
        String grantToken = body.path("GrantToken").asText(null);
        if (grantToken != null && !grantToken.isBlank()) {
            String grantKey = grantKeyArn(grantToken, body.path("GrantId").asText(null), region);
            return grantKey == null
                    ? List.of(keyWildcard(region, accountId, partition))
                    : List.of(grantKey);
        }
        Set<String> resources = new LinkedHashSet<>();
        resources.add(keyTarget(body, "KeyId", region, accountId, partition));
        String grantKey = grantKeyArn(grantToken, body.path("GrantId").asText(null), region);
        if (grantKey != null) {
            resources.add(grantKey);
        }
        return new ArrayList<>(resources);
    }

    /**
     * The keys a {@code RevokeGrant} acts on: the key its {@code KeyId} names, which the
     * handler resolves, and the key the grant its {@code GrantId} names belongs to, which the
     * handler deletes. They coincide for a request naming a grant on its own key, and naming
     * both closes the pair that names one key while revoking another key's grant. The
     * handler never reads a {@code GrantToken} on {@code RevokeGrant}, so the grant resolves
     * by its {@code GrantId} alone.
     */
    private List<String> revokeGrantTargets(JsonNode body, String region, String accountId,
                                             String partition) {
        Set<String> resources = new LinkedHashSet<>();
        resources.add(keyTarget(body, "KeyId", region, accountId, partition));
        String grantKey = grantKeyArn(null, body.path("GrantId").asText(null), region);
        if (grantKey != null) {
            resources.add(grantKey);
        }
        return new ArrayList<>(resources);
    }

    /** The stored ARN of the key the request's grant belongs to, or null when none resolves. */
    private String grantKeyArn(String grantToken, String grantId, String region) {
        if (kmsService == null) {
            return null;
        }
        return kmsService.get()
                .findGrantKeyArn(grantToken, grantId, region)
                .orElse(null);
    }

    /**
     * The alias and the key a {@code CreateAlias} names, as the alias permissions page requires
     * the caller to hold {@code kms:CreateAlias} on both.
     */
    private List<String> createAliasTargets(JsonNode body, String region, String accountId,
                                            String partition) {
        Set<String> resources = new LinkedHashSet<>();
        String aliasArn = aliasArn(body.path("AliasName").asText(null), region, accountId, partition);
        if (aliasArn != null) {
            resources.add(aliasArn);
        }
        resources.add(keyTarget(body, "TargetKeyId", region, accountId, partition));
        return new ArrayList<>(resources);
    }

    /**
     * The three resources an {@code UpdateAlias} acts on, as the alias permissions page lists
     * them: the alias, the key it points at and the key it will point at.
     */
    private List<String> updateAliasTargets(JsonNode body, String region, String accountId,
                                            String partition) {
        Set<String> resources = new LinkedHashSet<>();
        addAliasAndItsKey(resources, body, region, accountId, partition);
        resources.add(keyTarget(body, "TargetKeyId", region, accountId, partition));
        return new ArrayList<>(resources);
    }

    /**
     * The alias and the key it points at, the two resources a {@code DeleteAlias} acts on. A
     * request naming no alias AWS would accept the name of names nothing to authorize against
     * but the wildcard, and the handler refuses it.
     */
    private List<String> deleteAliasTargets(JsonNode body, String region, String accountId,
                                            String partition) {
        Set<String> resources = new LinkedHashSet<>();
        addAliasAndItsKey(resources, body, region, accountId, partition);
        return resources.isEmpty() ? List.of("*") : new ArrayList<>(resources);
    }

    /**
     * The alias the request's {@code AliasName} names, and the key that alias points at when it
     * resolves to one. Nothing is added for a name that is missing or that AWS would refuse.
     */
    private void addAliasAndItsKey(Set<String> resources, JsonNode body, String region,
                                   String accountId, String partition) {
        String aliasName = body.path("AliasName").asText(null);
        String aliasArn = aliasArn(aliasName, region, accountId, partition);
        if (aliasArn == null) {
            return;
        }
        resources.add(aliasArn);
        if (kmsService != null) {
            String keyArn = kmsService.get().findKeyArn(aliasName, region).orElse(null);
            if (keyArn != null) {
                resources.add(keyArn);
            }
        }
    }

    /** The alias's ARN, or null when the request names no alias AWS would accept the name of. */
    private static String aliasArn(String aliasName, String region, String accountId, String partition) {
        if (aliasName == null || !aliasName.startsWith("alias/")) {
            return null;
        }
        return new AwsArnUtils.Arn(partition, "kms", region, accountId, aliasName).toString();
    }

    /** Every key the account holds in the region, the pattern a permissive policy still matches. */
    private static String keyWildcard(String region, String accountId, String partition) {
        return new AwsArnUtils.Arn(partition, "kms", region, accountId, "key/*").toString();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    /** The request's JSON or CBOR body, buffered so the handler still reads it, or null when it has none. */
    public JsonNode readJsonBody(ContainerRequestContext ctx) {
        Object cached = ctx.getProperty("floci.bufferedJsonBody");
        if (cached instanceof JsonNode node) {
            return node;
        }
        InputStream in = ctx.getEntityStream();
        if (in == null) {
            return null;
        }
        byte[] body;
        try {
            body = in.readAllBytes();
        } catch (IOException e) {
            ctx.setEntityStream(new ByteArrayInputStream(new byte[0]));
            return null;
        }
        ctx.setEntityStream(new ByteArrayInputStream(body));
        if (body.length == 0) {
            return null;
        }
        try {
            JsonNode node = RequestBodyReader.readTree(body, ctx.getMediaType(), objectMapper);
            ctx.setProperty("floci.bufferedJsonBody", node);
            return node;
        } catch (Exception e) {
            return null;
        }
    }

    private String extractSegmentAfter(String path, String segment) {
        String marker = "/" + segment + "/";
        int idx = path.indexOf(marker);
        if (idx < 0) return null;
        String after = path.substring(idx + marker.length());
        // take only the first segment (stop at next /)
        int slash = after.indexOf('/');
        return slash > 0 ? after.substring(0, slash) : after;
    }

    // ── STS ─────────────────────────────────────────────────────────────────────
    /**
     * Uses the serving operation, as {@link #buildLogResources} does: {@code AssumeRole} is
     * authorized against the role it names, so a policy can allow assuming one role and not another.
     * Every other operation keeps {@code *}, so a {@code RoleArn} it never reads cannot choose its resource.
     */
    public List<String> buildStsResources(String action, ContainerRequestContext ctx) {
        return List.of("sts:AssumeRole".equals(action) ? buildAssumeRoleArn(ctx) : "*");
    }

    private String buildAssumeRoleArn(ContainerRequestContext ctx) {
        String roleArn = firstFormParam(ctx, "RoleArn");
        // Untrimmed: the handler assumes the role named by this exact string.
        return roleArn == null || roleArn.isBlank() ? "*" : roleArn;
    }

    /**
     * A Query-protocol parameter, read from the same place the handler reads it. For a form POST
     * that is the body alone: {@code AwsQueryController} dispatches on the form parameters and never
     * sees the URL query, so authorizing a URL value would check one resource and act on another.
     * A value with an invalid percent escape is read as absent, so the resource stays the default
     * and the request goes on to be answered as it is without enforcement.
     */
    private String firstFormParam(ContainerRequestContext ctx, String name) {
        if (RequestBodyReader.isForm(ctx)) {
            try {
                return RequestBodyReader.formField(ctx, name);
            } catch (IllegalArgumentException malformed) {
                return null;
            }
        }
        return ctx.getUriInfo().getQueryParameters().getFirst(name);
    }
}
