package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.services.cloudformation.CloudFormationQueryHandler;
import io.github.hectorvent.floci.services.ec2.Ec2QueryHandler;
import io.github.hectorvent.floci.services.cloudwatch.metrics.CloudWatchMetricsQueryHandler;
import io.github.hectorvent.floci.services.cognito.CognitoJsonHandler;
import io.github.hectorvent.floci.services.elasticache.ElastiCacheQueryHandler;
import io.github.hectorvent.floci.services.iam.IamQueryHandler;
import io.github.hectorvent.floci.services.iam.StsQueryHandler;
import io.github.hectorvent.floci.services.rds.RdsQueryHandler;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    // Extracts service name: Credential=AKID/20260227/us-east-1/iam/aws4_request → "iam"
    private static final Pattern SERVICE_PATTERN =
            Pattern.compile("Credential=\\S+/\\d{8}/[^/]+/([^/]+)/");

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
            "CreatePolicy", "GetPolicy", "DeletePolicy", "ListPolicies",
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
            "GetAccountSummary", "GetAccountAuthorizationDetails"
    );

    private static final Set<String> EC2_ACTIONS = Set.of(
            "RunInstances", "DescribeInstances", "TerminateInstances", "StartInstances", "StopInstances",
            "RebootInstances", "DescribeInstanceStatus", "DescribeInstanceAttribute", "ModifyInstanceAttribute",
            "CreateVpc", "DescribeVpcs", "DeleteVpc", "ModifyVpcAttribute", "DescribeVpcAttribute",
            "CreateDefaultVpc", "AssociateVpcCidrBlock", "DisassociateVpcCidrBlock",
            "CreateSubnet", "DescribeSubnets", "DeleteSubnet", "ModifySubnetAttribute",
            "CreateSecurityGroup", "DescribeSecurityGroups", "DeleteSecurityGroup",
            "AuthorizeSecurityGroupIngress", "AuthorizeSecurityGroupEgress",
            "RevokeSecurityGroupIngress", "RevokeSecurityGroupEgress",
            "DescribeSecurityGroupRules", "ModifySecurityGroupRules",
            "UpdateSecurityGroupRuleDescriptionsIngress", "UpdateSecurityGroupRuleDescriptionsEgress",
            "CreateKeyPair", "DescribeKeyPairs", "DeleteKeyPair", "ImportKeyPair",
            "DescribeImages",
            "CreateTags", "DeleteTags", "DescribeTags",
            "CreateInternetGateway", "DescribeInternetGateways", "DeleteInternetGateway",
            "AttachInternetGateway", "DetachInternetGateway",
            "CreateRouteTable", "DescribeRouteTables", "DeleteRouteTable",
            "AssociateRouteTable", "DisassociateRouteTable", "CreateRoute", "DeleteRoute",
            "AllocateAddress", "AssociateAddress", "DisassociateAddress", "ReleaseAddress", "DescribeAddresses",
            "DescribeAvailabilityZones", "DescribeRegions", "DescribeAccountAttributes",
            "DescribeInstanceTypes"
    );

    private final CloudFormationQueryHandler cloudFormationQueryHandler;
    private final ElastiCacheQueryHandler elastiCacheQueryHandler;
    private final RdsQueryHandler rdsQueryHandler;
    private final SqsQueryHandler sqsQueryHandler;
    private final SnsQueryHandler snsQueryHandler;
    private final SesQueryHandler sesQueryHandler;
    private final IamQueryHandler iamQueryHandler;
    private final StsQueryHandler stsQueryHandler;
    private final CloudWatchMetricsQueryHandler cloudWatchMetricsQueryHandler;
    private final CognitoJsonHandler cognitoJsonHandler;
    private final Ec2QueryHandler ec2QueryHandler;
    private final ResolvedServiceCatalog catalog;
    private final RegionResolver regionResolver;

    @Inject
    public AwsQueryController(CloudFormationQueryHandler cloudFormationQueryHandler,
                              ElastiCacheQueryHandler elastiCacheQueryHandler,
                              RdsQueryHandler rdsQueryHandler,
                              SqsQueryHandler sqsQueryHandler, SnsQueryHandler snsQueryHandler,
                              SesQueryHandler sesQueryHandler,
                              IamQueryHandler iamQueryHandler, StsQueryHandler stsQueryHandler,
                              CloudWatchMetricsQueryHandler cloudWatchMetricsQueryHandler,
                              CognitoJsonHandler cognitoJsonHandler,
                              Ec2QueryHandler ec2QueryHandler,
                              ResolvedServiceCatalog catalog,
                              RegionResolver regionResolver) {
        this.cloudFormationQueryHandler = cloudFormationQueryHandler;
        this.elastiCacheQueryHandler = elastiCacheQueryHandler;
        this.rdsQueryHandler = rdsQueryHandler;
        this.sqsQueryHandler = sqsQueryHandler;
        this.snsQueryHandler = snsQueryHandler;
        this.sesQueryHandler = sesQueryHandler;
        this.iamQueryHandler = iamQueryHandler;
        this.stsQueryHandler = stsQueryHandler;
        this.cloudWatchMetricsQueryHandler = cloudWatchMetricsQueryHandler;
        this.cognitoJsonHandler = cognitoJsonHandler;
        this.ec2QueryHandler = ec2QueryHandler;
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
            return null;
        }

        String service = resolveService(authorization, action);
        LOG.debugv("Query protocol service={0} action={1}", service, action);

        String region = regionResolver.resolveRegion(httpHeaders);

        return switch (service) {
            case "sqs" -> sqsQueryHandler.handle(action, formParams, region);
            case "sns" -> snsQueryHandler.handle(action, formParams, region);
            case "iam" -> iamQueryHandler.handle(action, formParams);
            case "sts" -> stsQueryHandler.handle(action, formParams);
            case "elasticache" -> elastiCacheQueryHandler.handle(action, formParams);
            case "rds" -> rdsQueryHandler.handle(action, formParams);
            case "email" -> sesQueryHandler.handle(action, formParams, region);
            case "monitoring" -> cloudWatchMetricsQueryHandler.handle(action, formParams, region);
            case "cloudformation" -> cloudFormationQueryHandler.handle(action, formParams, region);
            case "cognito-idp" -> handleCognitoQuery(action, formParams, region);
            case "ec2" -> ec2QueryHandler.handle(action, formParams, region);
            default -> xmlErrorResponse("UnknownService",
                    "Unknown or unsupported service: " + service, 400);
        };
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
            "CreateReplicationGroup", "DescribeReplicationGroups", "DeleteReplicationGroup",
            "CreateUser", "DescribeUsers", "ModifyUser", "DeleteUser"
    );

    private static final Set<String> CLOUDWATCH_ACTIONS = Set.of(
            "PutMetricData", "ListMetrics", "GetMetricStatistics", "GetMetricData",
            "PutMetricAlarm", "DescribeAlarms", "DeleteAlarms", "SetAlarmState",
            "ListTagsForResource", "TagResource", "UntagResource"
    );

    private static final Set<String> RDS_ACTIONS = Set.of(
            "CreateDBInstance", "DescribeDBInstances", "DeleteDBInstance",
            "ModifyDBInstance", "RebootDBInstance",
            "CreateDBCluster", "DescribeDBClusters", "DeleteDBCluster", "ModifyDBCluster",
            "CreateDBParameterGroup", "DescribeDBParameterGroups",
            "DeleteDBParameterGroup", "ModifyDBParameterGroup", "DescribeDBParameters"
    );

    private static final Set<String> CLOUDFORMATION_ACTIONS = Set.of(
            "CreateStack", "DeleteStack", "UpdateStack", "DescribeStacks",
            "ListStacks", "GetTemplate", "ValidateTemplate",
            "CreateChangeSet", "DeleteChangeSet", "DescribeChangeSet", "ExecuteChangeSet", "ListChangeSets",
            "DescribeStackEvents", "DescribeStackResources", "ListStackResources", "DescribeStackResource",
            "SetStackPolicy", "GetStackPolicy", "ListStackSets", "DescribeStackSet", "CreateStackSet"
    );

    private static final Set<String> SES_ACTIONS = Set.of(
            "VerifyEmailIdentity", "VerifyEmailAddress", "VerifyDomainIdentity",
            "DeleteIdentity", "ListIdentities", "GetIdentityVerificationAttributes",
            "SendEmail", "SendRawEmail", "GetSendQuota", "GetSendStatistics",
            "GetAccountSendingEnabled", "ListVerifiedEmailAddresses", "DeleteVerifiedEmailAddress",
            "SetIdentityNotificationTopic", "GetIdentityNotificationAttributes",
            "GetIdentityDkimAttributes"
    );

    private static final Set<String> COGNITO_ACTIONS = Set.of(
            "AdminCreateUser", "AdminGetUser", "AdminDeleteUser", "AdminSetUserPassword",
            "AdminUpdateUserAttributes", "AdminUserGlobalSignOut", "ListUsers",
            "InitiateAuth", "AdminInitiateAuth", "RespondToAuthChallenge",
            "SignUp", "ConfirmSignUp", "ChangePassword", "ForgotPassword",
            "ConfirmForgotPassword", "GetUser", "UpdateUserAttributes",
            "CreateUserPool", "DescribeUserPool", "ListUserPools", "DeleteUserPool",
            "CreateUserPoolClient", "DescribeUserPoolClient", "ListUserPoolClients", "DeleteUserPoolClient",
            "CreateGroup", "GetGroup", "ListGroups", "DeleteGroup",
            "AdminAddUserToGroup", "AdminRemoveUserFromGroup", "AdminListGroupsForUser"
    );

    private String resolveService(String authorization, String action) {
        if (authorization != null && !authorization.isEmpty()) {
            Matcher m = SERVICE_PATTERN.matcher(authorization);
            if (m.find()) {
                String scope = m.group(1).toLowerCase();
                ServiceDescriptor descriptor = catalog.byCredentialScope(scope).orElse(null);
                if (descriptor != null && descriptor.supportsProtocol(ServiceProtocol.QUERY)) {
                    return descriptor.externalKey();
                }
            }
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
        // SQS actions are numerous and not enumerated — fall back to sqs only for
        // requests that arrived without an Authorization header (raw/test clients)
        return "sqs";
    }

    private Response xmlErrorResponse(String code, String message, int status) {
        String xml = new XmlBuilder()
                .start("ErrorResponse")
                  .start("Error")
                    .elem("Type", "Sender")
                    .elem("Code", code)
                    .elem("Message", message)
                  .end("Error")
                  .elem("RequestId", UUID.randomUUID().toString())
                .end("ErrorResponse")
                .build();
        return Response.status(status).entity(xml).type(MediaType.APPLICATION_XML).build();
    }
}
