package io.floci.aws.core.common;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * connections and API destinations; Application Auto Scaling, for its scalable targets and their tags; Redshift
 * Data, for the Redshift Serverless workgroups it runs statements on and the targets of its sessions; Payment
 * Cryptography, for the key an alias names and the tags of a key; KMS, for the key an id, an alias or an
 * ARN names, the key an alias points at, a grant is on or a ciphertext was encrypted under, and the tags of a key;
 * EMR, for the clusters of its instance groups and a
 * tag call's resource and the tags of a cluster or a session; IAM, for the user, group, role, instance profile or
 * server certificate a name names, path included, the user an access key, an SSH public key or a service-specific
 * credential belongs to, the role a service-linked role deletion task deleted and the user who signs a request; IAM
 * Identity Center, for the accounts its permission
 * sets are provisioned to and the tags of an instance,
 * a permission set, an application or a trusted token issuer; Resource Explorer, for its default view and the tags
 * of an index or a view; OAM and RAM, for the sinks, invitations and shares of other accounts they act on, OAM for
 * the tags of a sink or a link, and RAM for the tags of a resource share;
 * Secrets Manager, for the secret a name or an ARN names, random suffix included, and its tags; EventBridge
 * Scheduler, for its default schedule group and its groups' tags;
 * CloudFormation, for the stack or the stack set a name names, whose id it minted, and the tags of a stack, a stack set
 * or a change set; S3 Tables, for the table a request names by its table bucket, namespace and name, and the tags of a
 * table bucket or a table; Lambda, for the layer version a layer version ARN selects, the image of a MicroVM and the
 * execution of a callback, and the tags of a function, an event source mapping, a web function, a MicroVM image or a
 * network connector; Glue, for the tags of a resource it tags; Step Functions, for the tags of a state machine, its
 * versions and executions, and an activity; CodeBuild, for the project a build belongs to and the tags of a project or
 * a report group; Athena, for the workgroup and the data catalog a request naming none uses, the workgroup a query
 * execution or a named query runs in, and the tags of a workgroup or a data catalog; Redshift Serverless, for the
 * tags of a namespace, a workgroup or a snapshot; DynamoDB, for the tags of a table and its indexes; ECR, for the
 * tags of a repository; Redshift, for the key a snapshot records
 * and the tags of a cluster, a snapshot, a parameter group, a subnet group, a snapshot copy grant, a snapshot
 * schedule, an event subscription or an integration; SSM, for the parameter a legacy ARN names, the document of
 * another account an association runs and the tags of a parameter, a document or an association; CloudFront, for the
 * key value store a name names and the tags of any resource it tags; ECS, for the revision a task definition
 * reference resolves to, its default cluster and the tags of a cluster, a task definition, a service, a capacity
 * provider, a task or a container instance; ACM, for the tags of a certificate; Firehose, for the tags of a delivery
 * stream; Cognito, for the tags of a user pool or an identity pool; OpenSearch, for the tags of a domain; Signer, for
 * the tags of a signing profile; Access Analyzer, for the tags of an analyzer; Managed Service for Apache Flink, for
 * the tags of an application; S3 Vectors, for the tags of a vector bucket or an index; Timestream for InfluxDB, for the
 * tags of a DB instance, a DB cluster, a parameter group or a backup; CloudTrail, for the tags of a trail; Budgets, for
 * the tags of a budget or a budget action; AWS FIS, for the tags of an experiment template, an experiment or an action;
 * CodePipeline, for the tags of a pipeline, a webhook or a custom action type; DataSync, for the tags of an agent, a
 * location or a task; Global Accelerator, for the tags of an accelerator, a listener or an endpoint group; EMR
 * Serverless, for the tags of an application, a job run or a session; EMR on EKS, for the tags of a virtual cluster, a
 * job run, a managed endpoint, a job template or a security configuration; Cost Explorer, for the tags of an anomaly
 * monitor or subscription; Security Hub, for the tags of a hub or a configuration policy; Control Tower, for the tags
 * of a landing zone or an enabled control; GuardDuty, for the tags of a detector or a Malware Protection plan; Cloud
 * Map, for the tags of a namespace or a service; Elastic Load Balancing, for the tags of a load balancer, a target
 * group, a listener, a rule or a Classic Load Balancer; SQS, for the tags of a queue; SNS, for the tags of a topic;
 * Kinesis Data Streams, for the tags of a stream or a consumer; Kinesis Video Streams, for the stream a name names,
 * whose creation time it minted, and the tags of a stream; EventBridge Pipes, for the tags of a pipe; Aurora DSQL,
 * for the tags of a cluster; EKS, for the tags of a cluster, a node group, a Fargate profile, an add-on, a Pod Identity
 * association or an access entry; Resource Groups, for the tags of a group; CloudWatch Logs, for a delivery, the
 * delivery destinations of a delivery source and the tags of a log group, a destination, a delivery source, a
 * delivery destination or a delivery; Pricing Calculator, for the tags of a workload estimate; Amazon Managed Service
 * for Prometheus, for the tags of a workspace or a rule groups namespace; AWS Config, for the config rule,
 * configuration recorder or conformance pack a name names, whose id it minted); looked up lazily by
 * {@code ResourceArnBuilder} and {@code IamConditionContextResolver} through {@code Instance<StoredResourceArns>} for
 * the namespace a request or an ARN is of, so IAM never depends on that service directly.</p>
 *
 * <p>Each answer is a lookup of what the service holds, asked for by an entry of the generated table ({@code find},
 * {@code through}, a default, {@code forwarded}, a type's {@code resourceTagKeys}): never a fact about what an operation acts on, which the table alone
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
     * coordinates (a layer version), or as the service's handler reads the ARN where it reads it first as the
     * caller's (a secret a full or partial ARN names, in the ARN's Region, before one another account holds under
     * that full ARN). Empty when the service has no such resource or does not resolve ARN selectors this way.
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
     * The tags of the resource stored under {@code arn}, in the account and Region the ARN names, by key, which the
     * resource-tag keys the table gives the ARN's type take as their values ({@code aws:ResourceTag/<key>}); empty
     * when the service holds no such resource or keeps no tags on it. Read on every request checked on such an ARN,
     * so it reads the one resource the ARN names and leaves the resource as it is (a secret's last-accessed date).
     */
    default Map<String, String> storedTags(String arn) {
        return Map.of();
    }

    /**
     * {@code tags} as {@link #storedTags} answers them: in their order, a copy the caller may keep, and a tag without
     * a value as an empty one, as a resource-tag condition key reads it.
     */
    /**
     * The value a request gives a variable the table names {@code Encoded} and a botocore shape's name, as the service
     * writes it into its ARNs: by default percent-encoded as RFC 3986 encodes a URI's path, every byte but its
     * unreserved characters and {@code /}, its hex digits in upper case. A service whose ARNs encode it otherwise
     * says so (CodeArtifact's package group patterns, in lower case).
     */
    default String encoded(String variable, String value) {
        return percentEncoded(value, false);
    }

    /**
     * The value percent-encoded as RFC 3986 encodes a URI's path: every UTF-8 byte but its unreserved characters
     * ({@code A-Z a-z 0-9 - . _ ~}) and {@code /}, the hex digits in lower case when {@code lowerCase}.
     */
    static String percentEncoded(String value, boolean lowerCase) {
        StringBuilder encoded = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if (c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9'
                    || c == '-' || c == '.' || c == '_' || c == '~' || c == '/') {
                encoded.append((char) c);
            } else {
                encoded.append('%').append(String.format(lowerCase ? "%02x" : "%02X", c));
            }
        }
        return encoded.toString();
    }

    static Map<String, String> withEmptyValues(Map<String, String> tags) {
        Map<String, String> copy = new LinkedHashMap<>();
        tags.forEach((key, value) -> copy.put(key, value == null ? "" : value));
        return copy;
    }

    /**
     * A statement a request carries, as its service's parser reads it: its {@code verb} ({@code SELECT}) and the names
     * of what it acts on, by the word the table gives each ({@code table}, {@code index}).
     */
    record Statement(String verb, Map<String, String> names) {
    }

    /**
     * The verb of {@code statement} and the names of what it acts on, as the service's own parser reads the
     * statement it runs (a DynamoDB PartiQL statement's table and index), which the table checks as the action of
     * that verb; empty when it reads no such statement, which its handler then refuses.
     */
    default Optional<Statement> statement(String statement) {
        return Optional.empty();
    }

    /**
     * The ARN of the user whose access key {@code accessKeyId} is, as the service that issued the key stores the user,
     * path included, or its account's root user's for a key of the root: the user an operation acting on the user who
     * signs it acts on (IAM's {@code GetUser} with no {@code UserName}). Empty for a key of no user, as a role
     * session's, which such an operation refuses, and for one the service did not issue.
     */
    default Optional<String> signedBy(String accessKeyId) {
        return Optional.empty();
    }

    /**
     * Whether the service acts on the resource {@code arn} names, one {@link #find} gave, for a request another service
     * forwards as the caller: false for a KMS key disabled or pending deletion, which KMS refuses such a request with,
     * as for a resource it does not hold. True for any other.
     */
    default boolean usable(String arn) {
        return true;
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
     * The default used for an omitted resource when its rule does not declare a default.
     * Services whose defaults apply only where declared can return empty.
     */
    default Optional<String> findImplicitDefault(String type, String region) {
        return findDefault(type, region);
    }

    /**
     * Whether an ARN naming another account than the caller's, {@code accountId}, names a resource the service acts on
     * in that account, never as the caller's resource of that name (the sink an OAM link is made to, a RAM invitation
     * sent to the caller, a share another account owns or a role STS assumes in its account; a CodeArtifact domain, an
     * ECR repository, a Redshift snapshot or a Signer profile a request names by its owner), so a request naming it
     * is checked on that ARN alone, as AWS checks it; false when the service may act on the caller's resource of that
     * name, or holds none such there where it acts only on those it holds. Whether the caller may use it is the
     * handler's to answer after the check. Read by the ARN alone, as it runs on every such request.
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

    /**
     * As {@link #findByMembers(String, List, String)}, with the paths of the members the values come from, as the
     * table writes them ({@code AlternateIdentifier.ExternalId.Id}), for a service whose request may name the resource
     * in more than one way, each the same number of values.
     */
    default Optional<String> findByMembers(String type, List<String> members, List<String> names, String region) {
        return findByMembers(type, names, region);
    }
}
