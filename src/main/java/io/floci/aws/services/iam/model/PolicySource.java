package io.floci.aws.services.iam.model;

/**
 * Where one of a caller's identity policy documents came from, which is what a simulation reports
 * for the statement that decided it.
 *
 * <p>The {@code Statement} type a result carries names the policy twice over: {@code SourcePolicyId}
 * is "the identifier of the policy that was provided as an input", and {@code SourcePolicyType} is
 * "the type of the policy", whose published values are {@code user}, {@code group}, {@code role},
 * {@code aws-managed}, {@code user-managed}, {@code resource} and {@code none}. Both members are
 * optional, and AWS's own example for a supplied document carries the first without the second:
 * {@code <SourcePolicyId>PolicyInputList.1</SourcePolicyId>} and no type at all. Its example for an
 * attached policy names the policy itself, {@code AmazonS3ReadOnlyAccess}.
 *
 * <p>A document no identifier names carries neither, which is what {@link #unnamed()} is: a grant
 * the account applies to an identity without attaching anything to it has no policy to name, and
 * AWS publishes no spelling for one.
 *
 * @param policyId   the identifier a result reports, or null when nothing names the document
 * @param policyType one of the published types, or null when the document has none
 */
public record PolicySource(String policyId, String policyType) {

    /** A policy embedded in the user that holds it. */
    public static final String TYPE_USER = "user";
    /** A policy embedded in a group the user belongs to. */
    public static final String TYPE_GROUP = "group";
    /** A policy embedded in the role that holds it. */
    public static final String TYPE_ROLE = "role";
    /** A managed policy AWS owns. */
    public static final String TYPE_AWS_MANAGED = "aws-managed";
    /** A managed policy the customer owns. */
    public static final String TYPE_USER_MANAGED = "user-managed";

    /**
     * A document the request supplied, named by the position it arrived under in
     * {@code PolicyInputList}, which is the identifier AWS's own example reports for one:
     * {@code PolicyInputList.1}. The position is the member's own index as the request wrote it,
     * so a list numbered past a gap names the position it used.
     */
    public static PolicySource input(String position) {
        return new PolicySource("PolicyInputList." + position, null);
    }

    /**
     * A policy embedded in the entity holding it, named by the policy's own name and the entity's
     * type. The attachment type is already spelled the way the published list spells it,
     * {@code user}, {@code group} or {@code role}, being what an exclusion identifier is compared
     * against.
     */
    public static PolicySource inline(String policyName, String attachmentType) {
        return new PolicySource(policyName, attachmentType);
    }

    /** A managed policy, named by its own name, as AWS's example names one, and by its owner. */
    public static PolicySource managed(String policyName, boolean awsOwned) {
        return new PolicySource(policyName, awsOwned ? TYPE_AWS_MANAGED : TYPE_USER_MANAGED);
    }

    /** A document nothing names, whose statements a result can still locate. */
    public static PolicySource unnamed() {
        return new PolicySource(null, null);
    }
}
