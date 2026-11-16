package io.floci.aws.services.iam;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.AwsQueryLists;
import io.floci.aws.services.iam.IamService.HeldPolicy;
import jakarta.ws.rs.core.MultivaluedMap;

import java.util.ArrayList;
import java.util.List;

/**
 * The policies a simulation leaves out, as {@code SimulatePrincipalPolicy}'s
 * {@code PolicyExclusionList} identifies them: "a list of policies to exclude from the simulation",
 * used "to test what the simulation result would be if a policy were removed, without changing
 * which policies are actually attached to the principal identified by PolicySourceArn".
 *
 * <p>Each entry is a {@code PolicyIdentifier}, which the reference marks a union: "Specify exactly
 * one of PolicyType, PolicyArn, or InlinePolicyIdentifier". The constraints ahead of the operation
 * hold every member of an entry to the shape the model declares for it, the type to its value set
 * and the inline identifier to its three required members, because the vendored data carries those;
 * what it does not carry is the union marker, so the entry is held to naming one of the three here.
 *
 * <p>The member states both answers, which is what this reads: "Syntactically invalid identifiers,
 * such as malformed ARNs or wildcards in disallowed positions, cause the request to fail with an
 * InvalidInput error. Syntactically valid identifiers that don't match any attached policy are
 * ignored." So an entry is refused for naming none or more than one of the three, for a
 * {@code PolicyArn} that parses as no ARN or leaves out a field every ARN carries, and for a
 * wildcard where the member admits none, and an identifier that names a policy the principal does
 * not hold leaves the simulation as it was.
 *
 * <p>{@code docs/services/iam.md} records the readings under Policy Simulation.
 */
public final class PolicyExclusions {

    /** What a request carrying no {@code PolicyExclusionList} asks to leave out, which is nothing. */
    public static final PolicyExclusions NONE = new PolicyExclusions(List.of());

    private static final String LIST = "PolicyExclusionList";
    private static final String INLINE = ".InlinePolicyIdentifier";

    private final List<Identifier> identifiers;

    private PolicyExclusions(List<Identifier> identifiers) {
        this.identifiers = identifiers;
    }

    /**
     * One entry of the list, carrying whichever of the three the entry named: a policy type, an
     * ARN, or an inline policy's name together with the entity it is attached to.
     */
    private record Identifier(String type, String arn, String policyName,
                              String attachmentType, String attachmentName) {
    }

    /**
     * The exclusions {@code params} asks for, refusing an entry the reference calls syntactically
     * invalid. Read before the policy source is resolved, since an input is answered before a
     * lookup in this package.
     */
    public static PolicyExclusions from(MultivaluedMap<String, String> params) {
        List<AwsQueryLists.IndexedMember> entries = AwsQueryLists.members(params, LIST, null);
        if (entries.isEmpty()) {
            return NONE;
        }
        List<Identifier> identifiers = new ArrayList<>(entries.size());
        for (AwsQueryLists.IndexedMember entry : entries) {
            identifiers.add(identifier(params, entry));
        }
        return new PolicyExclusions(identifiers);
    }

    private static Identifier identifier(MultivaluedMap<String, String> params,
                                         AwsQueryLists.IndexedMember entry) {
        String path = "policyExclusionList." + entry.index() + ".member";
        String type = member(params, entry.prefix() + ".PolicyType");
        String arn = member(params, entry.prefix() + ".PolicyArn");
        String policyName = member(params, entry.prefix() + INLINE + ".PolicyName");
        String attachmentType = member(params, entry.prefix() + INLINE + ".AttachmentType");
        String attachmentName = member(params, entry.prefix() + INLINE + ".AttachmentName");
        boolean inline = policyName != null || attachmentType != null || attachmentName != null;
        int named = (type == null ? 0 : 1) + (arn == null ? 0 : 1) + (inline ? 1 : 0);
        if (named != 1) {
            throw invalidInput(path, "a policy identifier must specify exactly one of PolicyType, "
                    + "PolicyArn or InlinePolicyIdentifier");
        }
        if (arn != null) {
            requireArnTheMemberAdmits(arn, path + ".policyArn");
            return new Identifier(null, arn, null, null, null);
        }
        if (inline) {
            return new Identifier(null, null, policyName, attachmentType, attachmentName);
        }
        return new Identifier(type, null, null, null, null);
    }

    /** A member the request carries, where a blank is a member it does not. */
    private static String member(MultivaluedMap<String, String> params, String key) {
        String value = params.getFirst(key);
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * The positions {@code PolicyArn} admits a wildcard in: "Wildcard characters are supported in
     * the resource name portion of the ARN to match multiple managed policies: use at most one
     * {@code *} ... and any number of {@code ?}". The resource type an ARN carries ahead of that
     * name is not the resource name, so it admits none either: a {@code poli?y/reader} written for
     * {@code policy/reader} is a wildcard in a position the member does not name, and matching it
     * would leave out a policy on an identifier AWS refuses.
     *
     * <p>A value that parses as no ARN is the malformed one the member names, and so is one that
     * leaves out a field every ARN carries: the partition, the service and the resource. Both are
     * answered in the wording this operation already answers a malformed {@code PolicySourceArn}
     * with. The region and the account are not among them, an AWS managed policy carrying no
     * region, so one left empty is an identifier that matches no policy rather than a malformed
     * one.
     */
    private static void requireArnTheMemberAdmits(String arn, String path) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException malformed) {
            throw malformedArn(arn);
        }
        if (parsed.partition().isBlank() || parsed.service().isBlank()
                || parsed.resource().isBlank()) {
            throw malformedArn(arn);
        }
        int name = parsed.resource().indexOf('/');
        String resourceType = name < 0 ? parsed.resource() : parsed.resource().substring(0, name);
        for (String field : List.of(parsed.partition(), parsed.service(), parsed.region(),
                parsed.accountId(), resourceType)) {
            if (field.indexOf('*') >= 0 || field.indexOf('?') >= 0) {
                throw invalidInput(path, "wildcard characters are supported only in the resource "
                        + "name portion of the ARN");
            }
        }
        if (parsed.resource().chars().filter(character -> character == '*').count() > 1) {
            throw invalidInput(path, "an ARN may carry at most one '*'");
        }
    }

    /** The sentence this package answers a malformed ARN with, wherever it reads one. */
    private static AwsException malformedArn(String arn) {
        return new AwsException("InvalidInput", "The ARN " + arn + " is not a valid ARN.", 400);
    }

    /**
     * No published message covers an entry this reads as invalid, so the wording is the modeled
     * exception's own sentence, which this package already answers a malformed delegation member
     * with, and the member is named the way the constraints ahead of the operation name one.
     */
    private static AwsException invalidInput(String path, String reason) {
        return new AwsException("InvalidInput", "The request was rejected because an invalid or "
                + "out-of-range value was supplied for '" + path + "': " + reason, 400);
    }

    /**
     * {@code held} without the policies these exclusions name, in the order it carried them.
     *
     * <p>{@code PolicyType} is the part a policy plays in the evaluation rather than the kind of
     * policy it is, which is what keeps {@code permission-boundary} in a set beside
     * {@code user-managed}: a boundary is a customer managed policy, so a type that meant the kind
     * would match it twice and leave {@code permission-boundary} standing for nothing. So
     * {@code inline}, {@code aws-managed} and {@code user-managed} reach the identity policies and
     * {@code permission-boundary} reaches the boundary alone.
     *
     * <p>{@code scp} matches nothing here, a simulation carrying no SCP ceiling, which is recorded
     * as deliberate, and {@code rcp} matches nothing because the member says so: "Resource control
     * policies (RCPs) are not supported in this release; identifiers that target RCPs are also
     * ignored."
     */
    public List<HeldPolicy> retained(List<HeldPolicy> held) {
        if (identifiers.isEmpty()) {
            return held;
        }
        List<HeldPolicy> retained = new ArrayList<>(held.size());
        for (HeldPolicy policy : held) {
            if (!excludes(policy)) {
                retained.add(policy);
            }
        }
        return retained;
    }

    /**
     * Whether the boundary stored for the principal is left out: by the type it plays, or by an ARN
     * that names it, an identifier naming a policy by ARN naming that policy wherever it enters the
     * simulation.
     */
    public boolean excludesBoundary(String boundaryArn) {
        if (boundaryArn == null) {
            return false;
        }
        return identifiers.stream().anyMatch(identifier ->
                "permission-boundary".equals(identifier.type())
                        || namesArn(identifier, boundaryArn));
    }

    private boolean excludes(HeldPolicy policy) {
        return identifiers.stream().anyMatch(identifier -> matches(identifier, policy));
    }

    private static boolean matches(Identifier identifier, HeldPolicy policy) {
        if (identifier.arn() != null) {
            return namesArn(identifier, policy.arn());
        }
        if (identifier.type() != null) {
            return matchesType(identifier.type(), policy);
        }
        return policy.arn() == null
                && identifier.policyName().equals(policy.policyName())
                && identifier.attachmentType().equals(policy.attachmentType())
                && identifier.attachmentName().equals(policy.attachmentName());
    }

    /**
     * An ARN the identifier names, matched a field at a time: the wildcards it carries were held to
     * the resource name portion when it was read, and matching per field keeps a {@code *} written
     * there from reaching across one.
     */
    private static boolean namesArn(Identifier identifier, String arn) {
        return identifier.arn() != null && arn != null
                && IamPolicyEvaluator.matchesArnCondition(identifier.arn(), arn);
    }

    private static boolean matchesType(String type, HeldPolicy policy) {
        return switch (type) {
            case "inline" -> policy.arn() == null;
            case "aws-managed" -> AwsManagedPolicies.isManagedPolicyArn(policy.arn());
            case "user-managed" -> policy.arn() != null
                    && !AwsManagedPolicies.isManagedPolicyArn(policy.arn());
            // permission-boundary is answered by excludesBoundary, scp and rcp by nothing, and the
            // constraints ahead of the operation have already refused any other value.
            default -> false;
        };
    }
}
