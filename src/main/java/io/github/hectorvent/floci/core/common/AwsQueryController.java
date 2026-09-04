package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.services.neptune.NeptuneQueryHandler;
import io.github.hectorvent.floci.services.neptune.NeptuneService;
import io.github.hectorvent.floci.services.docdb.DocDbQueryHandler;
import io.github.hectorvent.floci.services.docdb.DocDbService;
import io.github.hectorvent.floci.services.autoscaling.AutoScalingQueryHandler;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationQueryHandler;
import io.github.hectorvent.floci.services.ec2.Ec2QueryHandler;
import io.github.hectorvent.floci.services.elasticbeanstalk.ElasticBeanstalkQueryHandler;
import io.github.hectorvent.floci.services.elb.ElbClassicQueryHandler;
import io.github.hectorvent.floci.services.elbv2.ElbV2QueryHandler;
import io.github.hectorvent.floci.services.cloudwatch.metrics.CloudWatchMetricsQueryHandler;
import io.github.hectorvent.floci.services.cognito.CognitoJsonHandler;
import io.github.hectorvent.floci.services.elasticache.ElastiCacheQueryHandler;
import io.github.hectorvent.floci.services.iam.IamQueryHandler;
import io.github.hectorvent.floci.services.iam.StsQueryHandler;
import io.github.hectorvent.floci.services.rds.RdsQueryHandler;
import io.github.hectorvent.floci.services.rds.RdsService;
import io.github.hectorvent.floci.services.redshift.RedshiftQueryHandler;
import io.github.hectorvent.floci.services.sns.SnsQueryHandler;
import io.github.hectorvent.floci.services.ses.SesQueryHandler;
import io.github.hectorvent.floci.services.sqs.SqsQueryHandler;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.Set;
import java.util.UUID;

/**
 * Generic dispatcher for all AWS services that use the Query Protocol (form-encoded POST, XML response).
 * Routes requests to the appropriate service handler based on the service name extracted from the
 * Authorization header's credential scope, falling back to action-name matching when no auth header
 * is present.
 *
 * <p>Currently supported services:
 * <ul>
 *   <li>SQS — form-encoded query protocol</li>
 *   <li>SNS — form-encoded query protocol</li>
 *   <li>IAM — form-encoded query protocol (global service)</li>
 *   <li>STS — form-encoded query protocol (global service)</li>
 * </ul>
 *
 * @see AwsJsonController
 */
@Path("/")
public class AwsQueryController {

    private static final Logger LOG = Logger.getLogger(AwsQueryController.class);

    private static final Set<String> STS_ACTIONS = Set.of(
            "AssumeRole", "AssumeRoleWithWebIdentity", "AssumeRoleWithSAML",
            "GetCallerIdentity", "GetSessionToken", "GetFederationToken",
            "DecodeAuthorizationMessage"
    );

    private static final Set<String> SNS_ACTIONS = Set.of(
            "CreateTopic", "DeleteTopic", "ListTopics", "GetTopicAttributes", "SetTopicAttributes",
            "Subscribe", "Unsubscribe", "ListSubscriptions", "ListSubscriptionsByTopic",
            "Publish", "PublishBatch", "TagResource", "UntagResource", "ListTagsForResource",
            "CreatePlatformApplication", "DeletePlatformApplication", "GetPlatformApplicationAttributes",
            "SetPlatformApplicationAttributes", "ListPlatformApplications",
            "CreatePlatformEndpoint", "DeleteEndpoint", "GetEndpointAttributes",
            "SetEndpointAttributes", "ListEndpointsByPlatformApplication",
            "CheckIfPhoneNumberIsOptedOut", "ListPhoneNumbersOptedOut",
            "OptInPhoneNumber", "SetSMSAttributes", "GetSMSAttributes",
            "GetSubscriptionAttributes", "SetSubscriptionAttributes", "ConfirmSubscription",
            "CreateSMSSandboxPhoneNumber", "DeleteSMSSandboxPhoneNumber",
            "GetSMSSandboxAccountStatus", "ListSMSSandboxPhoneNumbers",
            "VerifySMSSandboxPhoneNumber", "AddPermission", "RemovePermission"
    );

    private static final Set<String> IAM_ACTIONS = Set.of(
            "CreateUser", "GetUser", "DeleteUser", "ListUsers", "UpdateUser",
            "CreateGroup", "GetGroup", "DeleteGroup", "ListGroups",
            "AddUserToGroup", "RemoveUserFromGroup", "ListGroupsForUser",
            "CreateRole", "GetRole", "DeleteRole", "ListRoles", "UpdateRole",
            "CreateServiceLinkedRole", "DeleteServiceLinkedRole", "GetServiceLinkedRoleDeletionStatus",
            "CreatePolicy", "GetPolicy", "DeletePolicy", "ListPolicies", "ListEntitiesForPolicy",
            "CreatePolicyVersion", "GetPolicyVersion", "DeletePolicyVersion",
            "ListPolicyVersions", "SetDefaultPolicyVersion",
            "AttachUserPolicy", "DetachUserPolicy", "ListAttachedUserPolicies",
            "AttachGroupPolicy", "DetachGroupPolicy", "ListAttachedGroupPolicies",
            "AttachRolePolicy", "DetachRolePolicy", "ListAttachedRolePolicies",
            "PutUserPolicy", "GetUserPolicy", "DeleteUserPolicy", "ListUserPolicies",
            "PutRolePolicy", "GetRolePolicy", "DeleteRolePolicy", "ListRolePolicies",
            "PutGroupPolicy", "GetGroupPolicy", "DeleteGroupPolicy", "ListGroupPolicies",
            "CreateAccessKey", "DeleteAccessKey", "ListAccessKeys", "UpdateAccessKey",
            "CreateInstanceProfile", "GetInstanceProfile", "DeleteInstanceProfile",
            "ListInstanceProfiles", "AddRoleToInstanceProfile",
            "RemoveRoleFromInstanceProfile", "ListInstanceProfilesForRole",
            "TagUser", "UntagUser", "ListUserTags",
            "TagRole", "UntagRole", "ListRoleTags",
            "TagPolicy", "UntagPolicy", "ListPolicyTags",
            "CreateLoginProfile", "GetLoginProfile", "DeleteLoginProfile", "UpdateLoginProfile",
            "GenerateCredentialReport", "GetCredentialReport",
            "GetAccountSummary", "GetAccountAuthorizationDetails",
            "SimulatePrincipalPolicy"
    );

    private static final Set<String> AUTOSCALING_ACTIONS = Set.of(
            "CreateLaunchConfiguration", "DescribeLaunchConfigurations", "DeleteLaunchConfiguration",
            "CreateAutoScalingGroup", "UpdateAutoScalingGroup", "DeleteAutoScalingGroup",
            "DescribeAutoScalingGroups", "SetDesiredCapacity",
            "StartInstanceRefresh", "DescribeInstanceRefreshes",
            "CreateOrUpdateTags", "DeleteTags",
            "DescribeAutoScalingInstances", "AttachInstances", "DetachInstances",
            "TerminateInstanceInAutoScalingGroup",
            "AttachLoadBalancerTargetGroups", "DetachLoadBalancerTargetGroups",
            "DescribeLoadBalancerTargetGroups", "AttachLoadBalancers", "DetachLoadBalancers",
            "PutLifecycleHook", "DeleteLifecycleHook", "DescribeLifecycleHooks",
            "CompleteLifecycleAction", "RecordLifecycleActionHeartbeat",
            "PutScalingPolicy", "DeletePolicy", "DescribePolicies",
            "DescribeScalingActivities",
            "DescribeAutoScalingNotificationTypes", "DescribeTerminationPolicyTypes",
            "DescribeAdjustmentTypes", "DescribeAccountLimits",
            "DescribeLifecycleHookTypes", "DescribeMetricCollectionTypes"
    );

    private static final Set<String> ELB_V2_ACTIONS = Set.of(
            "CreateLoadBalancer", "DescribeLoadBalancers", "DeleteLoadBalancer",
            "ModifyLoadBalancerAttributes", "DescribeLoadBalancerAttributes",
            "SetSecurityGroups", "SetSubnets", "SetIpAddressType",
            "CreateTargetGroup", "DescribeTargetGroups", "ModifyTargetGroup", "DeleteTargetGroup",
            "ModifyTargetGroupAttributes", "DescribeTargetGroupAttributes",
            "RegisterTargets", "DeregisterTargets", "DescribeTargetHealth",
            "CreateListener", "DescribeListeners", "ModifyListener", "DeleteListener",
            "AddListenerCertificates", "RemoveListenerCertificates", "DescribeListenerCertificates",
            "CreateRule", "DescribeRules", "ModifyRule", "DeleteRule", "SetRulePriorities",
            "AddTags", "RemoveTags", "DescribeTags",
            "DescribeSSLPolicies", "DescribeAccountLimits"
    );

    private static final Set<String> EC2_ACTIONS = Set.of(
            "RunInstances", "CreateFleet", "DescribeInstances", "TerminateInstances", "StartInstances", "StopInstances",
            "RebootInstances", "DescribeInstanceStatus", "DescribeInstanceAttribute", "ModifyInstanceAttribute",
            "CreateVpc", "DescribeVpcs", "DeleteVpc", "ModifyVpcAttribute", "DescribeVpcAttribute",
            "DescribeVpcEndpointServices", "CreateVpcEndpoint", "DescribeVpcEndpoints", "DeleteVpcEndpoints",
            "DescribePrefixLists",
            "CreateDefaultVpc", "AssociateVpcCidrBlock", "DisassociateVpcCidrBlock",
            "CreateSubnet", "DescribeSubnets", "DeleteSubnet", "ModifySubnetAttribute",
            "CreateSecurityGroup", "DescribeSecurityGroups", "DeleteSecurityGroup",
            "AuthorizeSecurityGroupIngress", "AuthorizeSecurityGroupEgress",
            "RevokeSecurityGroupIngress", "RevokeSecurityGroupEgress",
            "DescribeSecurityGroupRules", "ModifySecurityGroupRules",
            "UpdateSecurityGroupRuleDescriptionsIngress", "UpdateSecurityGroupRuleDescriptionsEgress",
            "CreateKeyPair", "DescribeKeyPairs", "DeleteKeyPair", "ImportKeyPair",
            "DescribeImages", "RegisterImage", "DescribeSnapshots",
            "CreateTags", "DeleteTags", "DescribeTags",
            "CreateInternetGateway", "DescribeInternetGateways", "DeleteInternetGateway",
            "AttachInternetGateway", "DetachInternetGateway",
            "CreateRouteTable", "DescribeRouteTables", "DeleteRouteTable",
            "AssociateRouteTable", "DisassociateRouteTable", "CreateRoute", "ReplaceRoute", "DeleteRoute",
            "CreateNetworkAcl", "DescribeNetworkAcls", "DeleteNetworkAcl",
            "CreateNetworkAclEntry", "ReplaceNetworkAclEntry", "DeleteNetworkAclEntry",
            "ReplaceNetworkAclAssociation",
            "CreateNatGateway", "DescribeNatGateways", "DeleteNatGateway",
            "CreateCapacityReservation", "DescribeCapacityReservations",
            "ModifyCapacityReservation", "CancelCapacityReservation",
            "AllocateAddress", "AssociateAddress", "DisassociateAddress", "ReleaseAddress", "DescribeAddresses",
            "DescribeAddressesAttribute",
            "DescribeIamInstanceProfileAssociations",
            "DescribeAvailabilityZones", "DescribeRegions", "DescribeAccountAttributes",
            "DescribeInstanceTypes", "DescribeInstanceTypeOfferings",
            "CreateLaunchTemplate", "CreateLaunchTemplateVersion", "DescribeLaunchTemplates", "DescribeLaunchTemplateVersions",
            "ModifyLaunchTemplate", "DeleteLaunchTemplate",
            "DescribeNetworkInterfaces",
            "CreateFlowLogs", "DescribeFlowLogs", "DeleteFlowLogs",
            "CreateVolume", "DescribeVolumes", "DeleteVolume", "AttachVolume", "DetachVolume"
    );

    private final CloudFormationQueryHandler cloudFormationQueryHandler;
    private final ElastiCacheQueryHandler elastiCacheQueryHandler;
    private final RdsQueryHandler rdsQueryHandler;
    private final NeptuneQueryHandler neptuneQueryHandler;
    private final NeptuneService neptuneService;
    private final DocDbQueryHandler docDbQueryHandler;
    private final DocDbService docDbService;
    private final RdsService rdsService;
    /** ARNs already reported as held by both services, so the warning is logged once each. */
    private final java.util.Set<String> reportedArnCollisions = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final SqsQueryHandler sqsQueryHandler;
    private final SnsQueryHandler snsQueryHandler;
    private final SesQueryHandler sesQueryHandler;
    private final IamQueryHandler iamQueryHandler;
    private final StsQueryHandler stsQueryHandler;
    private final CloudWatchMetricsQueryHandler cloudWatchMetricsQueryHandler;
    private final CognitoJsonHandler cognitoJsonHandler;
    private final Ec2QueryHandler ec2QueryHandler;
    private final ElbV2QueryHandler elbV2QueryHandler;
    private final ElbClassicQueryHandler elbClassicQueryHandler;
    private final AutoScalingQueryHandler autoScalingQueryHandler;
    private final ElasticBeanstalkQueryHandler elasticBeanstalkQueryHandler;
    private final RedshiftQueryHandler redshiftQueryHandler;
    private final ResolvedServiceCatalog catalog;
    private final RegionResolver regionResolver;

    @Inject
    public AwsQueryController(CloudFormationQueryHandler cloudFormationQueryHandler,
                              ElastiCacheQueryHandler elastiCacheQueryHandler,
                              RdsQueryHandler rdsQueryHandler,
                              NeptuneQueryHandler neptuneQueryHandler,
                              NeptuneService neptuneService,
                              DocDbQueryHandler docDbQueryHandler,
                              DocDbService docDbService,
                              RdsService rdsService,
                              SqsQueryHandler sqsQueryHandler, SnsQueryHandler snsQueryHandler,
                              SesQueryHandler sesQueryHandler,
                              IamQueryHandler iamQueryHandler, StsQueryHandler stsQueryHandler,
                              CloudWatchMetricsQueryHandler cloudWatchMetricsQueryHandler,
                              CognitoJsonHandler cognitoJsonHandler,
                              Ec2QueryHandler ec2QueryHandler,
                              ElbV2QueryHandler elbV2QueryHandler,
                              ElbClassicQueryHandler elbClassicQueryHandler,
                              AutoScalingQueryHandler autoScalingQueryHandler,
                              ElasticBeanstalkQueryHandler elasticBeanstalkQueryHandler,
                              RedshiftQueryHandler redshiftQueryHandler,
                              ResolvedServiceCatalog catalog,
                              RegionResolver regionResolver) {
        this.cloudFormationQueryHandler = cloudFormationQueryHandler;
        this.elastiCacheQueryHandler = elastiCacheQueryHandler;
        this.rdsQueryHandler = rdsQueryHandler;
        this.neptuneQueryHandler = neptuneQueryHandler;
        this.neptuneService = neptuneService;
        this.docDbQueryHandler = docDbQueryHandler;
        this.docDbService = docDbService;
        this.rdsService = rdsService;
        this.sqsQueryHandler = sqsQueryHandler;
        this.snsQueryHandler = snsQueryHandler;
        this.sesQueryHandler = sesQueryHandler;
        this.iamQueryHandler = iamQueryHandler;
        this.stsQueryHandler = stsQueryHandler;
        this.cloudWatchMetricsQueryHandler = cloudWatchMetricsQueryHandler;
        this.cognitoJsonHandler = cognitoJsonHandler;
        this.ec2QueryHandler = ec2QueryHandler;
        this.elbV2QueryHandler = elbV2QueryHandler;
        this.elbClassicQueryHandler = elbClassicQueryHandler;
        this.autoScalingQueryHandler = autoScalingQueryHandler;
        this.elasticBeanstalkQueryHandler = elasticBeanstalkQueryHandler;
        this.redshiftQueryHandler = redshiftQueryHandler;
        this.catalog = catalog;
        this.regionResolver = regionResolver;
    }

    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_XML)
    public Response dispatch(
            @HeaderParam("Authorization") String authorization,
            @Context HttpHeaders httpHeaders,
            MultivaluedMap<String, String> formParams) {

        String action = formParams.getFirst("Action");
        if (action == null) {
            action = formParams.getFirst("Operation");
        }
        if (action == null) {
            return xmlErrorResponse("MissingAction",
                    "The request must contain the parameter Action", 400);
        }

        String service = resolveService(authorization, action);
        LOG.debugv("Query protocol service={0} action={1}", service, action);

        String region = regionResolver.resolveRegion(httpHeaders);

        try {
            return dispatchToHandler(service, action, formParams, authorization, region);
        } catch (AwsException e) {
            // Handlers that don't render their own error XML would otherwise reach
            // AwsExceptionMapper, which emits JSON — the same JSON-on-the-XML-wire defect.
            // The declared code and status must survive; only the encoding changes.
            return xmlErrorResponse(e.getErrorCode(), e.getMessage(), e.getHttpStatus(),
                    e.getHttpStatus() >= 500 ? "Receiver" : "Sender");
        } catch (Exception e) {
            // A handler bug (e.g. an NPE) must never leak Quarkus's JSON error page onto
            // the Query/XML wire — SDK parsers fail before they can surface anything useful.
            LOG.errorv(e, "Unhandled error dispatching Query action {0} for service {1}", action, service);
            return xmlErrorResponse("InternalFailure",
                    "Unexpected error: " + e.getMessage(), 500, "Receiver");
        }
    }

    private Response dispatchToHandler(String service, String action,
                                       MultivaluedMap<String, String> formParams,
                                       String authorization, String region) {
        return switch (service) {
            case "sqs" -> sqsQueryHandler.handle(action, formParams, region);
            case "sns" -> snsQueryHandler.handle(action, formParams, region);
            case "iam" -> iamQueryHandler.handle(action, formParams, authorization);
            case "sts" -> stsQueryHandler.handle(action, formParams);
            case "elasticache" -> elastiCacheQueryHandler.handle(action, formParams, region);
            case "rds" -> {
                // Neptune signs requests with "rds" credential scope (same wire protocol).
                // Route to Neptune when Engine=neptune (create ops), when the cluster/instance
                // already exists in Neptune storage (describe/modify/delete ops), or when a
                // tagging ResourceName is a Neptune ARN.
                String engine = formParams.getFirst("Engine");
                String clusterId = formParams.getFirst("DBClusterIdentifier");
                String instanceId = formParams.getFirst("DBInstanceIdentifier");

                Response identifierClash = rdsAlreadyHoldsIdentifier(action, formParams, region);
                if (identifierClash != null) {
                    yield identifierClash;
                }

                if ("neptune".equalsIgnoreCase(engine)
                        || neptuneService.hasCluster(clusterId)
                        || neptuneService.hasInstance(instanceId)
                        || neptuneService.hasResourceWithArn(formParams.getFirst("ResourceName"))) {
                    yield neptuneQueryHandler.handle(action, formParams);
                }

                if ("docdb".equalsIgnoreCase(engine)
                       || docDbService.hasCluster(clusterId, region)
                       || docDbService.hasInstance(instanceId, region)
                       || namesDocDbResource(formParams.getFirst("ResourceName"), region)) {
                        yield docDbQueryHandler.handle(action, formParams);
                }
                yield rdsQueryHandler.handle(action, formParams, region);
            }
            case "neptune" -> isFamilyListing(action, formParams)
                    ? rdsQueryHandler.handle(action, formParams, region)
                    : neptuneQueryHandler.handle(action, formParams);
            case "docdb" -> {
                // The same check as on the rds scope: DocumentDB is reachable under either, and a
                // create that skipped it here would put a second record under an ARN RDS owns.
                Response clash = rdsAlreadyHoldsIdentifier(action, formParams, region);
                if (clash != null) {
                    yield clash;
                }
                // The list form answers for the whole RDS family on either endpoint; the RDS
                // handler assembles it from both stores.
                if (isFamilyListing(action, formParams)) {
                    yield rdsQueryHandler.handle(action, formParams, region);
                }
                yield docDbQueryHandler.handle(action, formParams);
            }
            case "email" -> sesQueryHandler.handle(action, formParams, region);
            case "monitoring" -> cloudWatchMetricsQueryHandler.handle(action, formParams, region);
            case "cloudformation" -> cloudFormationQueryHandler.handle(action, formParams, region);
            case "cognito-idp" -> handleCognitoQuery(action, formParams, region);
            case "ec2" -> ec2QueryHandler.handle(action, formParams, region);
            case "elasticloadbalancing" -> isElbClassicRequest(action, formParams)
                    ? elbClassicQueryHandler.handle(action, formParams, region)
                    : elbV2QueryHandler.handle(action, formParams, region);
            case "autoscaling" -> autoScalingQueryHandler.handle(action, formParams, region);
            case "elasticbeanstalk" -> elasticBeanstalkQueryHandler.handle(action, formParams, region);
            case "redshift" -> redshiftQueryHandler.handle(action, formParams);
            default -> xmlErrorResponse("UnknownService",
                    "Unknown or unsupported service: " + service, 400);
        };
    }

    /** The API version a Classic (v1) Elastic Load Balancing request declares. */
    private static final String ELB_CLASSIC_API_VERSION = "2012-06-01";

    /** The API version an ELBv2 (ALB/NLB) request declares. */
    private static final String ELB_V2_API_VERSION = "2015-12-01";

    /**
     * Whether an {@code elasticloadbalancing} request is for the Classic (2012-06-01) API.
     *
     * <p>Classic ELB and ELBv2 share an endpoint host <em>and</em> a credential scope, so the
     * service name cannot separate them — the same in-case split the {@code rds} scope already
     * needs for Neptune and DocumentDB. What separates them is the API version, which every
     * Query-protocol request carries as the {@code Version} form parameter and which both service
     * models declare. That is the discriminator used here, in preference to guessing from the
     * shape of the parameters: a client that says {@code Version=2012-06-01} has told us which API
     * it is speaking, and answering it from the other one is the defect this routing exists to fix.
     *
     * <p>The parameter shape is only a fallback, for a hand-rolled client that omitted
     * {@code Version}. Then an action unique to one API decides, and for the action names both
     * APIs define, the presence of a Classic-only parameter does: {@code LoadBalancerName} or
     * {@code LoadBalancerNames} identify a load balancer by name, which is Classic's addressing
     * model, whereas ELBv2 uses {@code LoadBalancerArn}, {@code Name} or {@code ResourceArns}.
     * With neither present the request stays with ELBv2, which is the pre-existing behaviour.
     */
    private static boolean isElbClassicRequest(String action, MultivaluedMap<String, String> formParams) {
        String version = formParams.getFirst("Version");
        if (ELB_CLASSIC_API_VERSION.equals(version)) {
            return true;
        }
        if (ELB_V2_API_VERSION.equals(version)) {
            return false;
        }
        if (ElbClassicQueryHandler.CLASSIC_ONLY_ACTIONS.contains(action)) {
            return true;
        }
        if (!ElbClassicQueryHandler.SHARED_ACTIONS.contains(action)) {
            return false;
        }
        return formParams.getFirst("LoadBalancerName") != null
                || formParams.getFirst("LoadBalancerNames.member.1") != null;
    }

    /**
     * The AlreadyExists answer when a create names an identifier RDS already holds here, or null.
     *
     * <p>One identifier space covers the whole RDS family: a live account refuses to create an
     * Aurora cluster named like an existing DocumentDB one. Answering that way is what stops two
     * services holding one identifier in one region, which no ARN could then tell apart — so the
     * check has to run on every route that reaches DocumentDB, not only the {@code rds} scope.
     *
     * <p>Only the identifier being created is checked: {@code CreateDBInstance} names its parent
     * cluster too, and that cluster existing in RDS is the normal case rather than a clash.
     */
    private Response rdsAlreadyHoldsIdentifier(String action,
                                               MultivaluedMap<String, String> formParams,
                                               String region) {
        if ("CreateDBCluster".equals(action) && rdsService.hasClusterOrInstance(
                formParams.getFirst("DBClusterIdentifier"), null, region)) {
            return xmlErrorResponse("DBClusterAlreadyExistsFault", "DB Cluster already exists", 400);
        }
        if ("CreateDBInstance".equals(action) && rdsService.hasClusterOrInstance(
                null, formParams.getFirst("DBInstanceIdentifier"), region)) {
            return xmlErrorResponse("DBInstanceAlreadyExists", "DB Instance already exists", 400);
        }
        return null;
    }

    /**
     * Whether a tagging {@code ResourceName} names a DocumentDB cluster or instance.
     *
     * <p>The tag actions carry no engine or identifier to route on — only the ARN of the resource
     * they address — so without reading it they reach the RDS handler, which does not hold
     * DocumentDB's records. The whole ARN is matched, not its trailing name: RDS and DocumentDB
     * share one ARN space, so a name both services use would otherwise be answered from the wrong
     * store, and an RDS parameter group or option group could be resolved as a cluster. Anything
     * DocumentDB does not hold under that exact ARN stays with RDS.
     *
     * <p>State persisted before creates were made to share one identifier space can still hold the
     * same identifier in both stores. DocumentDB answers it, which is what the identifier checks
     * above already do for every other action on that record — describe, modify and delete
     * included — so one identifier keeps one answer. The collision is logged once, because the
     * RDS record behind it is unreachable on this scope and only deleting one of the two fixes it.
     */
    private static boolean isFamilyListing(String action, MultivaluedMap<String, String> formParams) {
        return switch (action) {
            case "DescribeDBClusters" -> isBlank(formParams.getFirst("DBClusterIdentifier"));
            case "DescribeDBInstances" -> isBlank(formParams.getFirst("DBInstanceIdentifier"));
            default -> false;
        };
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private boolean namesDocDbResource(String resourceName, String region) {
        if (!docDbService.hasResourceWithArn(resourceName)) {
            return false;
        }
        String identifier = resourceName.substring(resourceName.lastIndexOf(':') + 1);
        if (rdsService.hasClusterOrInstance(identifier, identifier, region)
                && reportedArnCollisions.add(resourceName)) {
            LOG.warnv("RDS and DocumentDB both hold {0}; DocumentDB answers for it, as it does for "
                    + "every other action on that identifier. Delete one of the two records.",
                    resourceName);
        }
        return true;
    }

    private Response handleCognitoQuery(String action, MultivaluedMap<String, String> formParams, String region) {
        // Cognito is primarily JSON 1.1, but we provide a bridge for Query protocol if hit.
        // Convert MultivaluedMap to JsonNode if needed, but for now just return UnsupportedOperation 
        // with Cognito namespace.
        String xml = new XmlBuilder()
                .start("ErrorResponse")
                  .start("Error")
                    .elem("Type", "Sender")
                    .elem("Code", "UnsupportedOperation")
                    .elem("Message", "Operation " + action + " is not supported by Cognito via Query protocol.")
                  .end("Error")
                  .elem("RequestId", UUID.randomUUID().toString())
                .end("ErrorResponse")
                .build();
        return Response.status(400).entity(xml).type(MediaType.APPLICATION_XML).build();
    }

    /**
     * Determines the target AWS service. Prefers the service name embedded in the
     * Authorization header credential scope. Falls back to action-name lookup when
     * the header is absent (e.g. raw HTTP testing without AWS SDK auth).
     */
    private static final Set<String> ELASTICACHE_ACTIONS = Set.of(
            "ValidateIamAuthToken",
            "CreateReplicationGroup", "DescribeReplicationGroups", "ModifyReplicationGroup", "DeleteReplicationGroup",
            "CreateUser", "DescribeUsers", "ModifyUser", "DeleteUser"
    );

    private static final Set<String> CLOUDWATCH_ACTIONS = Set.of(
            "PutMetricData", "ListMetrics", "GetMetricStatistics", "GetMetricData",
            "PutMetricAlarm", "DescribeAlarms", "DeleteAlarms", "SetAlarmState",
            "ListTagsForResource", "TagResource", "UntagResource",
            "PutDashboard", "GetDashboard", "ListDashboards", "DeleteDashboards"
    );

    private static final Set<String> ELASTIC_BEANSTALK_ACTIONS = Set.of(
            "CreateApplication", "DescribeApplications", "UpdateApplication", "DeleteApplication",
            "CreateApplicationVersion", "DescribeApplicationVersions", "DeleteApplicationVersion",
            "CreateEnvironment", "DescribeEnvironments", "UpdateEnvironment", "TerminateEnvironment",
            "DescribeConfigurationSettings", "CheckDNSAvailability", "ListAvailableSolutionStacks"
    );

    private static final Set<String> RDS_ACTIONS = Set.of(
            "CreateDBInstance", "DescribeDBInstances", "DeleteDBInstance",
            "ModifyDBInstance", "RebootDBInstance",
            "DescribeOrderableDBInstanceOptions",
            "CreateDBSubnetGroup", "DescribeDBSubnetGroups", "ModifyDBSubnetGroup", "DeleteDBSubnetGroup",
            "AddTagsToResource", "ListTagsForResource", "RemoveTagsFromResource",
            "CreateDBCluster", "DescribeDBClusters", "DeleteDBCluster", "ModifyDBCluster",
            "AddRoleToDBCluster", "RemoveRoleFromDBCluster",
            "DescribeGlobalClusters",
            "CreateDBParameterGroup", "DescribeDBParameterGroups",
            "DeleteDBParameterGroup", "ModifyDBParameterGroup", "DescribeDBParameters",
            "DescribeDBProxies", "CreateDBProxy", "ModifyDBProxy", "DeleteDBProxy",
            "RegisterDBProxyTargets", "DeregisterDBProxyTargets",
            "DescribeDBProxyTargetGroups", "ModifyDBProxyTargetGroup", "DescribeDBProxyTargets",
            "CreateDBClusterParameterGroup", "DescribeDBClusterParameterGroups",
            "ModifyDBClusterParameterGroup", "DeleteDBClusterParameterGroup",
            "DescribeDBClusterParameters",
            "CreateOptionGroup", "DescribeOptionGroups", "ModifyOptionGroup", "DeleteOptionGroup",
            "DescribeDBSnapshots", "DescribeDBClusterSnapshots"
    );

    private static final Set<String> CLOUDFORMATION_ACTIONS = Set.of(
            "CreateStack", "DeleteStack", "UpdateStack", "DescribeStacks", "UpdateTerminationProtection",
            "ListStacks", "ListExports", "GetTemplate", "GetTemplateSummary", "ValidateTemplate",
            "CreateChangeSet", "DeleteChangeSet", "DescribeChangeSet", "ExecuteChangeSet", "ListChangeSets",
            "DescribeStackEvents", "DescribeStackResources", "ListStackResources", "DescribeStackResource",
            "SetStackPolicy", "GetStackPolicy",
            "ListStackSets", "DescribeStackSet", "CreateStackSet", "UpdateStackSet", "DeleteStackSet",
            "CreateStackInstances", "ListStackInstances", "DescribeStackInstance", "DeleteStackInstances",
            "ListStackSetOperations", "DescribeStackSetOperation"
    );

    private static final Set<String> SES_ACTIONS = Set.of(
            "VerifyEmailIdentity", "VerifyEmailAddress", "VerifyDomainIdentity",
            "DeleteIdentity", "ListIdentities", "GetIdentityVerificationAttributes",
            "SendEmail", "SendRawEmail", "GetSendQuota", "GetSendStatistics",
            "GetAccountSendingEnabled", "ListVerifiedEmailAddresses", "DeleteVerifiedEmailAddress",
            "SetIdentityNotificationTopic", "GetIdentityNotificationAttributes",
            "SetIdentityFeedbackForwardingEnabled",
            "SetIdentityDkimEnabled",
            "VerifyDomainDkim",
            "SetIdentityHeadersInNotificationsEnabled",
            "SetIdentityMailFromDomain", "GetIdentityMailFromDomainAttributes",
            "GetIdentityDkimAttributes",
            "PutIdentityPolicy", "GetIdentityPolicies", "ListIdentityPolicies", "DeleteIdentityPolicy",
            "CreateTemplate", "UpdateTemplate", "GetTemplate", "DeleteTemplate",
            "ListTemplates", "SendTemplatedEmail", "SendBulkTemplatedEmail",
            "TestRenderTemplate",
            "CreateCustomVerificationEmailTemplate", "GetCustomVerificationEmailTemplate",
            "ListCustomVerificationEmailTemplates", "UpdateCustomVerificationEmailTemplate",
            "DeleteCustomVerificationEmailTemplate", "SendCustomVerificationEmail",
            "CreateConfigurationSet", "DescribeConfigurationSet",
            "ListConfigurationSets", "DeleteConfigurationSet",
            "CreateConfigurationSetEventDestination",
            "UpdateConfigurationSetEventDestination",
            "DeleteConfigurationSetEventDestination",
            "UpdateConfigurationSetSendingEnabled",
            "CreateConfigurationSetTrackingOptions",
            "UpdateConfigurationSetTrackingOptions",
            "DeleteConfigurationSetTrackingOptions",
            "UpdateConfigurationSetReputationMetricsEnabled",
            "PutConfigurationSetDeliveryOptions",
            "CreateReceiptRuleSet", "DescribeReceiptRuleSet", "ListReceiptRuleSets",
            "DeleteReceiptRuleSet", "SetActiveReceiptRuleSet", "DescribeActiveReceiptRuleSet"
    );

    private static final Set<String> COGNITO_ACTIONS = Set.of(
            "AdminCreateUser", "AdminGetUser", "AdminDeleteUser", "AdminSetUserPassword",
            "AdminUpdateUserAttributes", "AdminUserGlobalSignOut", "ListUsers",
            "InitiateAuth", "AdminInitiateAuth", "RespondToAuthChallenge", "AdminRespondToAuthChallenge",
            "SignUp", "ConfirmSignUp", "ChangePassword", "ForgotPassword",
            "ConfirmForgotPassword", "GetUser", "UpdateUserAttributes",
            "CreateUserPool", "DescribeUserPool", "ListUserPools", "UpdateUserPool", "DeleteUserPool",
            "TagResource", "UntagResource", "ListTagsForResource",
            "CreateUserPoolClient", "DescribeUserPoolClient", "ListUserPoolClients", "DeleteUserPoolClient",
            "CreateGroup", "GetGroup", "ListGroups", "DeleteGroup",
            "AdminAddUserToGroup", "AdminRemoveUserFromGroup", "AdminListGroupsForUser"
    );

    private static final Set<String> REDSHIFT_ACTIONS = Set.of(
            "CreateCluster", "DescribeClusters", "DeleteCluster",
            "CreateClusterSnapshot", "DescribeClusterSnapshots", "DeleteClusterSnapshot", "RestoreFromClusterSnapshot",
            "ModifyCluster", "RebootCluster",
            "CreateClusterParameterGroup", "DescribeClusterParameterGroups", "DescribeClusterParameters", "DeleteClusterParameterGroup",
            "ModifyClusterParameterGroup",
            "CreateClusterSubnetGroup", "DescribeClusterSubnetGroups", "ModifyClusterSubnetGroup", "DeleteClusterSubnetGroup",
            "CreateTags", "DeleteTags", "DescribeTags"
    );

    private String resolveService(String authorization, String action) {
        ServiceDescriptor descriptor = SigV4CredentialScope.serviceName(authorization)
                .flatMap(catalog::byCredentialScope)
                .orElse(null);
        if (descriptor != null && descriptor.supportsProtocol(ServiceProtocol.QUERY)) {
            return descriptor.externalKey();
        }
        return inferServiceFromAction(action);
    }

    private String inferServiceFromAction(String action) {
        if (STS_ACTIONS.contains(action)) {
            return "sts";
        }
        if (IAM_ACTIONS.contains(action)) {
            return "iam";
        }
        if (SNS_ACTIONS.contains(action)) {
            return "sns";
        }
        if (ELASTICACHE_ACTIONS.contains(action)) {
            return "elasticache";
        }
        if (RDS_ACTIONS.contains(action)) {
            return "rds";
        }
        if (CLOUDWATCH_ACTIONS.contains(action)) {
            return "monitoring";
        }
        if (CLOUDFORMATION_ACTIONS.contains(action)) {
            return "cloudformation";
        }
        if (SES_ACTIONS.contains(action)) {
            return "email";
        }
        if (COGNITO_ACTIONS.contains(action)) {
            return "cognito-idp";
        }
        if (EC2_ACTIONS.contains(action)) {
            return "ec2";
        }
        if (ELB_V2_ACTIONS.contains(action)
                || ElbClassicQueryHandler.CLASSIC_ONLY_ACTIONS.contains(action)) {
            return "elasticloadbalancing";
        }
        if (AUTOSCALING_ACTIONS.contains(action)) {
            return "autoscaling";
        }
        if (ELASTIC_BEANSTALK_ACTIONS.contains(action)) {
            return "elasticbeanstalk";
        }
        if (REDSHIFT_ACTIONS.contains(action)) {
            return "redshift";
        }
        // SQS actions are numerous and not enumerated — fall back to sqs only for
        // requests that arrived without an Authorization header (raw/test clients)
        return "sqs";
    }

    private Response xmlErrorResponse(String code, String message, int status) {
        return xmlErrorResponse(code, message, status, "Sender");
    }

    private Response xmlErrorResponse(String code, String message, int status, String type) {
        String xml = new XmlBuilder()
                .start("ErrorResponse")
                  .start("Error")
                    .elem("Type", type)
                    .elem("Code", code)
                    .elem("Message", message)
                  .end("Error")
                  .elem("RequestId", UUID.randomUUID().toString())
                .end("ErrorResponse")
                .build();
        return Response.status(status).entity(xml).type(MediaType.APPLICATION_XML).build();
    }
}
