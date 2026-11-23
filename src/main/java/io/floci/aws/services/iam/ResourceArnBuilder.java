package io.floci.aws.services.iam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsQueryServiceResolver;
import io.floci.aws.core.common.AwsRegions;
import io.floci.aws.core.common.RequestPathLabels;
import io.floci.aws.core.common.RequestResources;
import io.floci.aws.core.common.ServicePrincipals;
import io.floci.aws.core.common.SigV4CredentialScope;
import io.floci.aws.core.common.StoredResourceArns;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Constructs the target resource ARN for a request so the policy evaluator
 * can match it against Resource patterns in policy documents.
 *
 * Returns {@code *} when the resource cannot be determined, which matches
 * permissive wildcard policies.
 */
@ApplicationScoped
public class ResourceArnBuilder {


    /** The only scheme IAM accepts on an OIDC provider URL, and the part its ARN drops. */
    private static final String OIDC_URL_SCHEME = "https://";

    private static final String DELEGATION_REQUEST_RESOURCE = "delegation-request/";

    /** What a service-linked role deletion task identifier starts with; see its resolver. */
    private static final String DELETION_TASK_PREFIX = "task" + ServiceLinkedRoles.PATH;


    private final ObjectMapper objectMapper;
    /**
     * Looked up lazily through a provider: this builder is constructed by the enforcement filter,
     * which IamService itself does not depend on, and a direct injection would close that loop.
     */
    private final Instance<IamService> iamService;
    /**
     * Looked up lazily for the same reason, to read the ARN a resource the request-resources table names by a name
     * its service follows with a minted id, or by members its minted ARN does not hold, is stored under.
     */
    private final Instance<StoredResourceArns> storedResourceArns;
    /**
     * Looked up lazily as well, to read a path's labels as the namespace's handlers read them where the router's
     * normalized path gives them otherwise (an S3 key's own slashes).
     */
    private final Instance<RequestPathLabels> requestPathLabels;

    @Inject
    public ResourceArnBuilder(ObjectMapper objectMapper, Instance<IamService> iamService,
                              Instance<StoredResourceArns> storedResourceArns,
                              Instance<RequestPathLabels> requestPathLabels) {
        this.objectMapper = objectMapper;
        this.iamService = iamService;
        this.storedResourceArns = storedResourceArns;
        this.requestPathLabels = requestPathLabels;
    }

    public ResourceArnBuilder(ObjectMapper objectMapper, Instance<IamService> iamService,
                              Instance<StoredResourceArns> storedResourceArns) {
        this(objectMapper, iamService, storedResourceArns, null);
    }

    ResourceArnBuilder(ObjectMapper objectMapper, Instance<IamService> iamService) {
        this(objectMapper, iamService, null);
    }

    public ResourceArnBuilder() {
        this(new ObjectMapper(), null);
    }

    public ResourceArnBuilder(ObjectMapper objectMapper) {
        this(objectMapper, null);
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
     * The resources a request is checked against, minted in {@code partition}, the request's, as its service mints its
     * own, so a Region no partition publishes gets the deployment's partition rather than {@code aws}.
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
        if (operation != null && operation.startsWith(credentialScope + ":") && RequestResources.reads(operation)) {
            return namedResources(credentialScope, operation, ctx, region, accountId, partition);
        }
        return switch (credentialScope) {
            case "iam"            -> buildIamArns(ctx, region, accountId);
            default               -> namedResources(credentialScope, operation, ctx, region, accountId, partition);
        };
    }

    /**
     * The resources a request of a namespace this builder has no branch for names, from the vendored table of
     * request resources (see {@link RequestResources}); {@code *} when it names none, or the operation is not one
     * of the namespace. A resource whose service mints an id after its name, or as all of its ARN, is the one the
     * namespace's {@link StoredResourceArns} finds under that name or the members naming it. A label of the
     * request's path is read as the namespace's {@link RequestPathLabels} reads it, where it reads one otherwise.
     */
    private List<String> namedResources(String credentialScope, String operation, ContainerRequestContext ctx,
                                        String region, String accountId, String partition) {
        if (operation == null || !operation.startsWith(credentialScope + ":")) {
            return List.of("*");
        }
        List<String> named = RequestResources.of(operation, ctx, partition, region, accountId,
                storedResourceArns(credentialScope), labels(credentialScope, ctx));
        return named.isEmpty() ? List.of("*") : named;
    }

    /**
     * The checks an operation AWS authorizes by actions its request's parts each take is made of, each action on the
     * resources its part names (see {@link RequestResources#targets}), read as {@link #buildResources} reads the
     * others: KMS's ReEncrypt, a DynamoDB transaction's items or PartiQL statements. Empty for every other operation.
     */
    public List<RequestResources.Target> targets(String credentialScope, String operation, ContainerRequestContext ctx,
                                                 String region, String accountId, String partition) {
        if (credentialScope == null || operation == null || !operation.startsWith(credentialScope + ":")
                || !RequestResources.reads(operation)) {
            return List.of();
        }
        return RequestResources.targets(operation, ctx, partition, region, accountId,
                storedResourceArns(credentialScope), labels(credentialScope, ctx));
    }

    /**
     * The labels of the request's path, as the route it was routed by gives them, each read as the namespace's
     * {@link RequestPathLabels} reads it where it reads one otherwise.
     */
    private Map<String, String> labels(String credentialScope, ContainerRequestContext ctx) {
        Map<String, String> labels = IamActionRegistry.pathLabels(credentialScope, ctx);
        RequestPathLabels read = requestPathLabels(credentialScope);
        Map<String, String> otherwise = read == null ? Map.of() : read.labels(ctx, labels);
        if (otherwise.isEmpty()) {
            return labels;
        }
        Map<String, String> merged = new HashMap<>(labels);
        merged.putAll(otherwise);
        return Map.copyOf(merged);
    }

    /** The registered reader of the namespace's path labels; null when there is none. */
    private RequestPathLabels requestPathLabels(String namespace) {
        if (requestPathLabels == null || requestPathLabels.isUnsatisfied()) {
            return null;
        }
        for (RequestPathLabels read : requestPathLabels) {
            if (read.namespace().equals(namespace)) {
                return read;
            }
        }
        return null;
    }

    /**
     * The requests the service of {@code operation}, the one the request is dispatched as, makes to another service as
     * the caller on what the resources it is checked on hold (see {@link RequestResources#forwarded}), each resource
     * looked up in its own service's stored state; none when the table gives the operation none.
     */
    public List<RequestResources.Forwarded> forwarded(String operation, List<String> resources, String accountId) {
        if (operation == null || operation.indexOf(':') < 0) {
            return List.of();
        }
        return RequestResources.forwarded(operation, resources, accountId,
                storedResourceArns(operation.substring(0, operation.indexOf(':'))), this::storedResourceArns);
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
     * service's tree, and a request id for the six delegation actions that name one.
     *
     * <p>The resolver is the value rather than a branch elsewhere, so this cannot become a list
     * that says an action is handled while the dispatch has nothing for it. The keys are also what
     * {@code IamActionResourcesTest} reads to tell a type one of these covers from a type nothing
     * resolves.
     */
    // Map.ofEntries rather than Map.of: this map passed ten pairs when the forward action gained
    // its resource, and Map.of has no overload beyond ten.
    static final Map<String, DerivedResolver> DERIVED_RESOLVERS = Map.ofEntries(
            Map.entry("CreateServiceLinkedRole", ResourceArnBuilder::serviceLinkedRoleArn),
            Map.entry("GetAccessKeyLastUsed",
                    (self, ctx, region, accountId) -> self.accessKeyOwnerArn(ctx, accountId)),
            Map.entry("GetServiceLinkedRoleDeletionStatus", ResourceArnBuilder::deletionTaskRoleArn),
            Map.entry("GenerateOrganizationsAccessReport",
                    ResourceArnBuilder::organizationsAccessReportArn),
            Map.entry("AssociateDelegationRequest", ResourceArnBuilder::delegationRequestArn),
            Map.entry("AcceptDelegationRequest", ResourceArnBuilder::delegationRequestArn),
            Map.entry("RejectDelegationRequest", ResourceArnBuilder::delegationRequestArn),
            Map.entry("GetDelegationRequest", ResourceArnBuilder::delegationRequestArn),
            Map.entry("UpdateDelegationRequest", ResourceArnBuilder::delegationRequestArn),
            Map.entry("SendDelegationToken", ResourceArnBuilder::delegationRequestArn),
            Map.entry("GetHumanReadableSummary", ResourceArnBuilder::summarizedRequestArn));

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
            // Without a stored role or a modelled Path member, the request names only the root path.
            if ("role".equals(resourceType) && IamRequestShapes.hasMember(action, "RoleName")
                    && !IamRequestShapes.hasMember(action, "Path")) {
                return List.of(AwsArnUtils.Arn.global(AwsRegions.partitionFor(region), "iam", accountId,
                        "role/" + name).toString());
            }
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
     * resource and the wildcard is the honest answer. {@code UpdateDelegationRequest} is here,
     * which corrects what this note said before: the customer-side page "Managing Permissions for
     * Delegation Requests" lists {@code iam:UpdateDelegationRequest} among the permissions an
     * administrator grants, describing it as "Forward a delegation request to an administrator for
     * approval", so it is an authorizable action and it names the request its id names, as its
     * siblings do.
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

    /**
     * The ARN a shared tagging route acts on, read where its controller reads it: after {@code /tags/} or
     * {@code /v1/tags/} in the path; on {@code POST /tags}, the body's {@code resourceArn}; and on the other
     * bare routes, the {@code resourceArn} query parameter. Null for any other route, or when the request
     * names none.
     */
    public String taggedArn(ContainerRequestContext ctx) {
        String path = IamActionRegistry.decodedPath(ctx);
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

    /**
     * A Query-protocol parameter, read from the same place the handler reads it. For a form POST
     * that is the body alone: {@code AwsQueryController} dispatches on the form parameters and never
     * sees the URL query, so authorizing a URL value would check one resource and act on another.
     * An invalid percent escape is rejected by the shared form decoder before authorization.
     */
    private String firstFormParam(ContainerRequestContext ctx, String name) {
        if (RequestBodyReader.isForm(ctx)) {
            return RequestBodyReader.formField(ctx, name);
        }
        return ctx.getUriInfo().getQueryParameters().getFirst(name);
    }
}
