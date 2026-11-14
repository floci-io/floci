package io.floci.aws.core.common;

import java.util.List;
import java.util.Optional;

/**
 * Finds the ARN a service stores a resource under, for a type of the request-resources table whose ARN only that
 * service knows: after the name a request gives ({@code connection/<name>/<id>}), or as all of it, a request naming
 * the resource by members its ARN does not hold
 * ({@code scalable-target/<id>}, named by its service namespace, resource id and scalable dimension;
 * {@code workgroup/<id>}, named by its name). Also the ARNs of the resources a request names through another of its
 * service's own (the cluster or workgroup a Data API session runs on, the cluster an EMR instance group is in, the
 * accounts an IAM Identity Center permission set is provisioned to). Also what a resource's record holds that a
 * request the operation's service forwards as the caller acts on (the key a Redshift snapshot is encrypted with), and
 * the ARN of that resource in the service that owns it (the key, in KMS).
 *
 * <p>The reader asks for every type of the namespace, not only for the ones whose ARN carries an id the service
 * mints, so a service may answer for a type whose ARN it does not mint: a key an alias names is the key the alias
 * points at.
 *
 * <p>Implemented by the service that owns such resources or whose requests name them (EventBridge, for its
 * connections and API destinations; Application Auto Scaling, for its scalable targets; Redshift Data, for the
 * Redshift Serverless workgroups it runs statements on and the targets of its sessions; Payment Cryptography, for
 * the key an alias names; EMR, for the clusters of its instance groups; IAM Identity Center, for the accounts its
 * permission sets are provisioned to; Resource Explorer, for its default view; OAM and RAM, for the sinks,
 * invitations and shares of other accounts they act on); looked up lazily by {@code ResourceArnBuilder} through {@code Instance<StoredResourceArns>}
 * for the namespace a request is of, so IAM never depends on that service directly.</p>
 *
 * <p>Each answer is a lookup of what the service holds, asked for by an entry of the generated table ({@code find},
 * {@code through}, a default, {@code forwarded}): never a fact about what an operation acts on, which the table alone
 * says, from botocore and the Service Reference, or from what AWS says where neither carries it.</p>
 */
public interface StoredResourceArns {

    /** The namespace of the resource types it finds, as the table names it ({@code events}). */
    String namespace();

    /**
     * The ARN the caller's resource of the type in the Region is stored under, named by {@code names}, the values the
     * request names it by in the order the table reads them: a connection's name, a scalable target's service
     * namespace, resource id and scalable dimension, or a workgroup's name or ARN; empty when there is none, or the
     * type is not one it finds.
     */
    Optional<String> find(String type, List<String> names, String region);

    /**
     * The resource resolved by an ARN in its own account and Region, without substituting the caller's
     * coordinates. Empty when the service has no such resource or does not resolve ARN selectors this way.
     * Used only for operations read by the generated request-resource table.
     */
    default Optional<String> resolveStoredArn(String arn) {
        return Optional.empty();
    }

    /**
     * The ARNs of the resources the caller's resource named by {@code value} in {@code member}, as the table writes
     * the member's path, runs on, is in or reaches in the Region, which a request naming it acts on (the cluster or
     * workgroup of a Data API session, by its {@code SessionId}; the cluster of an instance group, by its
     * {@code InstanceGroups[].InstanceGroupId}; the accounts of a permission set, by its {@code PermissionSetArn});
     * empty when the caller has none of that name, or the member is not one it reads.
     */
    default List<String> through(String member, String value, String region) {
        return List.of();
    }

    /**
     * What the resource stored under {@code arn}, in the account and Region the ARN names, records in {@code member},
     * as botocore names that member of the type's record (a Redshift Serverless snapshot's {@code kmsKeyId}, the key
     * it is encrypted with); empty when the service holds no such resource, it records nothing there, or the member
     * is not one it reads. A variable the ARN leaves open ({@code *}) matches whatever the handler would act on in
     * its place (a provisioned snapshot of any cluster, for a restore naming none).
     */
    default Optional<String> recorded(String arn, String member) {
        return Optional.empty();
    }

    /**
     * Whether the resource {@code arn} names, one {@link #find} gave, lets every principal of its account have a
     * service act on it through that service, as the key policy AWS writes for an AWS managed KMS key does: a request
     * another service forwards as the caller then needs no permission of the caller's on it. False for any other.
     */
    default boolean grantsItsAccountThroughServices(String arn) {
        return false;
    }

    /**
     * As {@link #findByMembers}, for a resource another account owns that the request names by that account, which its
     * service lets the caller reach when the resource's policy allows it (a Redshift Serverless snapshot shared by its
     * owner): the ARN that account's resource is stored under; empty when there is none, or the service finds none
     * of another account.
     */
    default Optional<String> findInAccount(String type, List<String> names, String account, String region) {
        return Optional.empty();
    }

    /**
     * The ARN of the caller's resource of the type the service acts on in the Region for a request naming none of
     * that type (the default view Resource Explorer searches with); empty when there is none, or the type is not one
     * it acts on so.
     */
    default Optional<String> findDefault(String type, String region) {
        return Optional.empty();
    }

    /**
     * Whether an ARN naming another account than the caller's, {@code accountId}, names a resource the service holds
     * in that account and acts on there, never as the caller's resource of that name (the sink an OAM link is made
     * to, a RAM invitation sent to the caller or a share another account owns), so a request naming it is checked on
     * that ARN alone; false when that account holds no such resource. Whether the caller may use it is the handler's
     * to answer after the check. Read by the ARN alone, as it runs on every such request.
     */
    default boolean reachedInItsAccount(String arn, String accountId) {
        return false;
    }

    /**
     * Resolves an explicit {@code find} selector. An empty list indicates an incomplete selector, which a service
     * may reject before IAM evaluation. This is distinct from an ordinary name filling an ARN's variables:
     * callback tokens require validation, but state-machine names do not name callback tokens.
     */
    default Optional<String> findByMembers(String type, List<String> names, String region) {
        return find(type, names, region);
    }
}
