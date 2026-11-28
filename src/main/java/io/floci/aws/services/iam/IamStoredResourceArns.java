package io.floci.aws.services.iam;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.StoredResourceArns;
import io.floci.aws.services.iam.model.IamGroup;
import io.floci.aws.services.iam.model.IamRole;
import io.floci.aws.services.iam.model.IamUser;
import io.floci.aws.services.iam.model.InstanceProfile;
import io.floci.aws.services.iam.model.ServerCertificate;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What IAM asks of its own resources a request names: the ARN a user, group, role, instance profile or server
 * certificate a name names is stored under, its path included, which the name alone does not carry; the user an access
 * key, an SSH public key or a service-specific credential belongs to; the role a service-linked role deletion task
 * deleted; the user who signs a request; what a delegation request records, which is the owner the condition key
 * the Service Reference publishes on that type is evaluated against; and the tags of a user or a role.
 *
 * <p>Both stores are reached lazily, through providers, rather than injected: the enforcement filter that asks is not a
 * dependency of {@link IamService}, and the delegation store is reached through {@link DelegationRequestOwnership}, as
 * {@code IamService} reaches it, so the lookup does not tie IAM's enforcement to the service that holds the requests.
 */
@ApplicationScoped
public class IamStoredResourceArns implements StoredResourceArns {

    private static final String DELEGATION_REQUEST = "delegation-request/";
    private static final String OWNER = "OwnerId";
    private static final String ROOT = ":root";

    private final Instance<DelegationRequestOwnership> delegationRequests;
    private final Instance<IamService> iamService;
    private final RegionResolver regionResolver;

    @Inject
    public IamStoredResourceArns(Instance<DelegationRequestOwnership> delegationRequests,
                                 Instance<IamService> iamService, RegionResolver regionResolver) {
        this.delegationRequests = delegationRequests;
        this.iamService = iamService;
        this.regionResolver = regionResolver;
    }

    @Override
    public String namespace() {
        return "iam";
    }

    /**
     * {@inheritDoc}
     *
     * <p>The caller's user, group, role, instance profile or server certificate of the one name given, as IAM stores
     * it: the path it was created or moved under is part of its ARN ({@code user/team/alice}), which a request naming
     * it by its name does not carry.
     */
    @Override
    public Optional<String> find(String type, List<String> names, String region) {
        if (names.size() != 1 || iamService.isUnsatisfied()) {
            return Optional.empty();
        }
        IamService iam = iamService.get();
        String account = regionResolver.getAccountId();
        String name = names.getFirst();
        Optional<String> arn = switch (type) {
            case "user" -> iam.findUser(account, name).map(IamUser::getArn);
            case "group" -> iam.findGroup(account, name).map(IamGroup::getArn);
            case "role" -> iam.findRole(account, name).map(IamRole::getArn);
            case "instance-profile" -> iam.findInstanceProfile(account, name).map(InstanceProfile::getArn);
            case "server-certificate" -> iam.findServerCertificate(name).map(ServerCertificate::getArn);
            default -> Optional.empty();
        };
        return arn.filter(stored -> !stored.isBlank());
    }

    /**
     * {@inheritDoc}
     *
     * <p>The caller's user an access key ({@code AccessKeyId}), an SSH public key ({@code SSHPublicKeyId}) or a
     * service-specific credential ({@code ServiceSpecificCredentialId}) belongs to, and the role a service-linked role
     * deletion task ({@code DeletionTaskId}) deleted. An access key is read in the caller's account alone, so another
     * account's key names no user of the caller's.
     */
    @Override
    public List<String> through(String member, String value, String region) {
        if (value == null || value.isBlank() || iamService.isUnsatisfied()) {
            return List.of();
        }
        IamService iam = iamService.get();
        Optional<String> arn = switch (member) {
            case "AccessKeyId" -> iam.findAccessKeyOwnerInThisAccount(value)
                    .flatMap(user -> find("user", List.of(user), region));
            case "SSHPublicKeyId" -> iam.findSshPublicKeyOwner(value)
                    .flatMap(user -> find("user", List.of(user), region));
            case "ServiceSpecificCredentialId" -> iam.findServiceSpecificCredentialOwner(value)
                    .flatMap(user -> find("user", List.of(user), region));
            case "DeletionTaskId" -> iam.findServiceLinkedRoleDeletionArn(value);
            default -> Optional.empty();
        };
        return arn.stream().toList();
    }

    /**
     * {@inheritDoc}
     *
     * <p>The stored user an IAM access key belongs to, or the account root's ARN for a key of the root, as the caller
     * the key resolves to names it; empty for any other key, such as a role session's.
     */
    @Override
    public Optional<String> signedBy(String accessKeyId) {
        if (accessKeyId == null || iamService.isUnsatisfied()) {
            return Optional.empty();
        }
        IamService iam = iamService.get();
        Optional<String> user = iam.findUserNameByAccessKeyId(accessKeyId)
                .flatMap(name -> find("user", List.of(name), ""));
        return user.isPresent() ? user : iam.resolveCallerArn(accessKeyId).filter(arn -> arn.endsWith(ROOT));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Only a delegation request records anything a condition key is read from, and only its owner, so an ARN
     * of any other type and any other member are answered with nothing. The id is what resolves a request, and
     * the account the ARN names is the one the owner is answered to.
     */
    @Override
    public Optional<String> recorded(String arn, String member) {
        if (!OWNER.equals(member) || !AwsArnUtils.isArn(arn) || delegationRequests.isUnsatisfied()) {
            return Optional.empty();
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        if (!"iam".equals(parsed.service()) || !parsed.resource().startsWith(DELEGATION_REQUEST)) {
            return Optional.empty();
        }
        String id = parsed.resource().substring(DELEGATION_REQUEST.length());
        if (id.isBlank()) {
            return Optional.empty();
        }
        return delegationRequests.get().ownerArnOf(parsed.accountId(), id);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The tags of the user or role the ARN names, in the account the ARN names, path included: the identity resources
     * {@code iam:ResourceTag} reads, "the tag attached to the identity resource (user or role)", which IAM and STS read
     * {@code aws:ResourceTag} on alike. So a policy on {@code sts:AssumeRole} or {@code iam:DeleteUser} keyed on the
     * tags of the role or user it names sees them. Any other ARN, and one whose path is not the stored one, names none.
     */
    @Override
    public Map<String, String> storedTags(String arn) {
        if (!AwsArnUtils.isArn(arn) || iamService.isUnsatisfied()) {
            return Map.of();
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        String resource = parsed.resource();
        if (!"iam".equals(parsed.service())) {
            return Map.of();
        }
        IamService iam = iamService.get();
        String name = resource.substring(resource.lastIndexOf('/') + 1);
        Optional<Map<String, String>> tags;
        if (resource.startsWith("role/")) {
            tags = iam.findRole(parsed.accountId(), name)
                    .filter(role -> IamService.roleArnMatches(arn, role))
                    .map(IamRole::getTags);
        } else if (resource.startsWith("user/")) {
            tags = iam.findUser(parsed.accountId(), name)
                    .filter(user -> namesTheSameResource(parsed, user.getArn()))
                    .map(IamUser::getTags);
        } else {
            tags = Optional.empty();
        }
        return tags.map(Map::copyOf).orElse(Map.of());
    }

    /** Whether {@code stored} is an ARN of the account, Region and resource {@code requested} names, as a role's is matched. */
    private static boolean namesTheSameResource(AwsArnUtils.Arn requested, String stored) {
        if (!AwsArnUtils.isArn(stored)) {
            return false;
        }
        AwsArnUtils.Arn held = AwsArnUtils.parse(stored);
        return requested.accountId().equals(held.accountId()) && requested.region().equals(held.region())
                && requested.resource().equals(held.resource());
    }
}
