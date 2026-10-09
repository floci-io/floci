package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsQueryServiceResolver;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.ServicePrincipals;
import io.github.hectorvent.floci.core.common.SigV4CredentialScope;
import io.github.hectorvent.floci.services.lambda.LambdaArnUtils;
import io.github.hectorvent.floci.services.lambda.durable.DurableTokens;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;

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

    /** What a service-linked role deletion task identifier starts with; see its resolver. */
    private static final String DELETION_TASK_PREFIX = "task" + ServiceLinkedRoles.PATH;

    private final ObjectMapper objectMapper;
    /**
     * Looked up lazily through a provider: this builder is constructed by the enforcement filter,
     * which IamService itself does not depend on, and a direct injection would close that loop.
     */
    private final Instance<IamService> iamService;

    @Inject
    public ResourceArnBuilder(ObjectMapper objectMapper, Instance<IamService> iamService) {
        this.objectMapper = objectMapper;
        this.iamService = iamService;
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
        if (credentialScope == null) {
            return List.of("*");
        }
        String path = ctx.getUriInfo().getPath();
        return switch (credentialScope) {
            case "s3"             -> List.of(buildS3Arn(path, region));
            case "lambda"         -> List.of(buildLambdaArn(path, region, accountId));
            case "sqs"            -> List.of(buildSqsArn(ctx, region, accountId));
            case "sns"            -> List.of(buildSnsArn(ctx, region, accountId));
            case "dynamodb"       -> buildDynamoDbArns(ctx, region, accountId);
            case "kinesis"        -> List.of(buildKinesisArn(ctx, region, accountId));
            case "secretsmanager" -> List.of(buildSecretsManagerArn(ctx, region, accountId));
            case "ssm"            -> List.of(buildSsmArn(ctx, region, accountId));
            case "kms"            -> List.of(buildKmsArn(path, region, accountId));
            case "iam"            -> buildIamArns(ctx, region, accountId);
            default               -> List.of("*");
        };
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
    static final Map<String, String> UNRESOLVED_TYPES = Map.of(
            "access-report", "GenerateOrganizationsAccessReport is not dispatched",
            "delegation-request", "the delegation request family is not dispatched",
            "role-template", "GetRoleTemplateVersion is not dispatched");

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
     * The actions resolved by a branch of their own in {@link #buildIamArns}, because the request
     * identifies the resource without naming it: a service principal for
     * {@code CreateServiceLinkedRole}, an access key id for {@code GetAccessKeyLastUsed}, and a
     * deletion task id for {@code GetServiceLinkedRoleDeletionStatus}.
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
            "GetServiceLinkedRoleDeletionStatus", ResourceArnBuilder::deletionTaskRoleArn);

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
     * <p>Only what the request itself names is resolved. An action that defaults to the calling
     * user when its {@code UserName} is omitted, as {@code GetUser} and {@code ChangePassword} do,
     * still resolves to {@code *}: the caller's identity is the filter's to supply, not this
     * builder's.
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
        return iamService.get().findUserNameByAccessKeyId(accessKeyId)
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
        return SigV4CredentialScope.accessKeyId(ctx.getHeaderString("Authorization"))
                .flatMap(accessKeyId -> iamService.get().findUserNameByAccessKeyId(accessKeyId))
                .flatMap(userName -> storedIamEntity("user", userName, accountId))
                .map(stored -> List.of(stored.arn()))
                .orElse(List.of("*"));
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
    private String buildLambdaArn(String path, String region, String accountId) {
        String executionArn = durableExecutionArn(path);
        if (executionArn == null) {
            executionArn = durableCallbackExecutionArn(path);
        }
        if (executionArn != null) {
            return executionArn;
        }
        // path: /2015-03-31/functions/name or similar
        String name = extractSegmentAfter(path, "functions");
        if (name == null) return "*";
        // strip qualifier if present
        int colon = name.indexOf(':');
        if (colon > 0) name = name.substring(0, colon);
        return AwsArnUtils.Arn.of("lambda", region, accountId, "function:" + name).toString();
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
        String queueUrl = ctx.getUriInfo().getQueryParameters().getFirst("QueueUrl");
        if (queueUrl == null) {
            // Try form param for Query-protocol
            queueUrl = firstFormParam(ctx, "QueueUrl");
        }
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

    private String toDynamoDbTableArn(String tableName, String region, String accountId) {
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
    private String buildSecretsManagerArn(ContainerRequestContext ctx, String region, String accountId) {
        JsonNode json = readJsonBody(ctx);
        if (json != null && json.isObject()) {
            if (json.hasNonNull("SecretId")) {
                String secretId = json.get("SecretId").asText().trim();
                if (!secretId.isEmpty()) {
                    if (AwsArnUtils.isArnFor(secretId, "secretsmanager")) {
                        return secretId;
                    }
                    return AwsArnUtils.Arn.of("secretsmanager", region, accountId, "secret:" + secretId).toString();
                }
            }
        }
        return AwsArnUtils.Arn.of("secretsmanager", region, accountId, "secret:*").toString();
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
    private String buildKmsArn(String path, String region, String accountId) {
        String keyId = extractSegmentAfter(path, "keys");
        if (keyId == null) return AwsArnUtils.Arn.of("kms", region, accountId, "key/*").toString();
        return AwsArnUtils.Arn.of("kms", region, accountId, "key/" + keyId).toString();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private JsonNode readJsonBody(ContainerRequestContext ctx) {
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
            JsonNode node = objectMapper.readTree(body);
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

    private String firstFormParam(ContainerRequestContext ctx, String name) {
        // Form params are typically available as query params in REST-Assured / JAX-RS
        String v = ctx.getUriInfo().getQueryParameters().getFirst(name);
        return v;
    }
}
