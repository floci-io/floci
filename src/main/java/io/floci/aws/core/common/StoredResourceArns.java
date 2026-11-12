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
 * accounts an IAM Identity Center permission set is provisioned to).
 *
 * <p>The reader asks for every type of the namespace, not only for the ones whose ARN carries an id the service
 * mints, so a service may answer for a type whose ARN it does not mint: a key an alias names is the key the alias
 * points at.
 *
 * <p>Implemented by the service that owns such resources or whose requests name them (EventBridge, for its
 * connections and API destinations; Application Auto Scaling, for its scalable targets; Redshift Data, for the
 * Redshift Serverless workgroups it runs statements on and the targets of its sessions; Payment Cryptography, for
 * the key an alias names; EMR, for the clusters of its instance groups; IAM Identity Center, for the accounts its
 * permission sets are provisioned to); looked up lazily by {@code ResourceArnBuilder} through {@code Instance<StoredResourceArns>}
 * for the namespace a request is of, so IAM never depends on that service directly.</p>
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
     * As {@link #findByMembers}, for a resource another account owns that the request names by that account, which its
     * service lets the caller reach when the resource's policy allows it (a Redshift Serverless snapshot shared by its
     * owner): the ARN that account's resource is stored under; empty when there is none, or the service finds none
     * of another account.
     */
    default Optional<String> findInAccount(String type, List<String> names, String account, String region) {
        return Optional.empty();
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
