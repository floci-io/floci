package io.floci.aws.services.iam;

import io.floci.aws.core.common.AwsProtocolClaimFilter;
import io.floci.aws.core.common.AwsQueryServiceResolver;
import io.floci.aws.core.common.IamOperation;
import io.floci.aws.core.common.ProtocolClaim;
import io.floci.aws.core.common.RequestResources;
import io.floci.aws.core.common.ResteasyRequests;
import io.floci.aws.core.common.SignedServiceRoute;
import io.floci.aws.core.common.WireProtocol;
import io.floci.aws.services.eks.EksController;
import io.floci.aws.services.s3.S3BucketSubresources;
import io.floci.aws.services.s3.S3ObjectActions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.core.ResteasyReactiveRequestContext;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Maps (credentialScope, httpMethod, requestPath) → IAM action string.
 *
 * For Query-protocol services (SQS, SNS, IAM, STS, ...) the Action form
 * parameter, or the controller's Operation fallback, is mapped directly to
 * {@code <service>:<Action>}.
 *
 * For REST-JSON services the first matching rule wins (specific before wildcard), and a request no
 * rule matches is the operation whose route in botocore's model it matches ({@link IamRestRoutes}),
 * when Floci can check that operation as AWS does.
 */
@ApplicationScoped
public class IamActionRegistry {

    private static final Logger LOG = Logger.getLogger(IamActionRegistry.class);

    private final IamRestRoutes restRoutes;

    public IamActionRegistry() {
        this(IamRestRoutes.vendored());
    }

    IamActionRegistry(IamRestRoutes restRoutes) {
        this.restRoutes = restRoutes;
    }

    /**
     * A route and the action it is checked as. A rule naming a query parameter matches only a request
     * carrying it with that value, as {@code GET /layers?find=LayerVersion} is GetLayerVersionByArn
     * where {@code GET /layers} is ListLayers.
     */
    private record ActionRule(String service, String method, Pattern pathPattern, String queryParameter,
                              String queryValue, String action) {

        boolean matches(String scope, String requestMethod, String path, ContainerRequestContext ctx) {
            return service.equals(scope) && method.equals(requestMethod) && pathPattern.matcher(path).find()
                    && (queryParameter == null
                    || queryValue.equals(ctx.getUriInfo().getQueryParameters().getFirst(queryParameter)));
        }
    }

    /** The routes the registry checks as an action of its own, each a rule of the scope it serves. */
    private static final List<ActionRule> RULES = List.of(
        // ── Lambda ──────────────────────────────────────────────────────────────
        // Code signing configs first: a config's function list ends in /functions, as ListFunctions does.
        rule("lambda", "GET",    "^/2020-04-22/code-signing-configs/[^/]+/functions$",
                "lambda:ListFunctionsByCodeSigningConfig"),
        rule("lambda", "GET",    "^/2020-04-22/code-signing-configs/[^/]+$",  "lambda:GetCodeSigningConfig"),
        rule("lambda", "PUT",    "^/2020-04-22/code-signing-configs/[^/]+$",  "lambda:UpdateCodeSigningConfig"),
        rule("lambda", "DELETE", "^/2020-04-22/code-signing-configs/[^/]+$",  "lambda:DeleteCodeSigningConfig"),
        rule("lambda", "GET",    "^/2020-04-22/code-signing-configs$",        "lambda:ListCodeSigningConfigs"),
        rule("lambda", "POST",   "^/2020-04-22/code-signing-configs$",        "lambda:CreateCodeSigningConfig"),
        rule("lambda", "GET",    ".*/functions$",                          "lambda:ListFunctions"),
        rule("lambda", "POST",   ".*/functions$",                          "lambda:CreateFunction"),
        rule("lambda", "GET",    ".*/functions/[^/]+$",                    "lambda:GetFunction"),
        rule("lambda", "PUT",    ".*/functions/[^/]+/code$",               "lambda:UpdateFunctionCode"),
        rule("lambda", "PUT",    ".*/functions/[^/]+/configuration$",      "lambda:UpdateFunctionConfiguration"),
        rule("lambda", "DELETE", ".*/functions/[^/]+$",                    "lambda:DeleteFunction"),
        rule("lambda", "POST",   ".*/functions/[^/]+/invocations$",        "lambda:InvokeFunction"),
        rule("lambda", "GET",    ".*/functions/[^/]+/aliases$",            "lambda:ListAliases"),
        rule("lambda", "POST",   ".*/functions/[^/]+/aliases$",            "lambda:CreateAlias"),
        rule("lambda", "GET",    ".*/functions/[^/]+/aliases/[^/]+$",      "lambda:GetAlias"),
        rule("lambda", "PUT",    ".*/functions/[^/]+/aliases/[^/]+$",      "lambda:UpdateAlias"),
        rule("lambda", "DELETE", ".*/functions/[^/]+/aliases/[^/]+$",      "lambda:DeleteAlias"),
        rule("lambda", "GET",    ".*/functions/[^/]+/policy$",             "lambda:GetPolicy"),
        rule("lambda", "POST",   ".*/functions/[^/]+/policy$",             "lambda:AddPermission"),
        rule("lambda", "DELETE", ".*/functions/[^/]+/policy/.+",           "lambda:RemovePermission"),
        rule("lambda", "GET",    ".*/event-source-mappings$",              "lambda:ListEventSourceMappings"),
        rule("lambda", "POST",   ".*/event-source-mappings$",              "lambda:CreateEventSourceMapping"),
        rule("lambda", "DELETE", ".*/event-source-mappings/[^/]+$",        "lambda:DeleteEventSourceMapping"),
        rule("lambda", "GET",    ".*/functions/[^/]+/url$",                "lambda:GetFunctionUrlConfig"),
        rule("lambda", "POST",   ".*/functions/[^/]+/url$",                "lambda:CreateFunctionUrlConfig"),
        rule("lambda", "PUT",    ".*/functions/[^/]+/url$",                "lambda:UpdateFunctionUrlConfig"),
        rule("lambda", "DELETE", ".*/functions/[^/]+/url$",                "lambda:DeleteFunctionUrlConfig"),
        rule("lambda", "GET",    ".*/functions/[^/]+/durable-executions$", "lambda:ListDurableExecutionsByFunction"),
        rule("lambda", "POST",   ".*/durable-executions/.+/checkpoint$",   "lambda:CheckpointDurableExecution"),
        rule("lambda", "GET",    ".*/durable-executions/.+/state$",        "lambda:GetDurableExecutionState"),
        rule("lambda", "GET",    ".*/durable-executions/.+/history$",      "lambda:GetDurableExecutionHistory"),
        rule("lambda", "POST",   ".*/durable-executions/.+/stop$",         "lambda:StopDurableExecution"),
        rule("lambda", "GET",    ".*/durable-executions/.+$",              "lambda:GetDurableExecution"),
        rule("lambda", "POST",   ".*/durable-execution-callbacks/.+/succeed$",
                "lambda:SendDurableExecutionCallbackSuccess"),
        rule("lambda", "POST",   ".*/durable-execution-callbacks/.+/fail$", "lambda:SendDurableExecutionCallbackFailure"),
        rule("lambda", "POST",   ".*/durable-execution-callbacks/.+/heartbeat$",
                "lambda:SendDurableExecutionCallbackHeartbeat"),
        rule("lambda", "GET",    ".*/event-source-mappings/[^/]+$",        "lambda:GetEventSourceMapping"),
        rule("lambda", "PUT",    ".*/event-source-mappings/[^/]+$",        "lambda:UpdateEventSourceMapping"),
        rule("lambda", "GET",    ".*/functions/[^/]+/configuration$",      "lambda:GetFunctionConfiguration"),
        rule("lambda", "GET",    ".*/functions/[^/]+/versions$",           "lambda:ListVersionsByFunction"),
        rule("lambda", "POST",   ".*/functions/[^/]+/versions$",           "lambda:PublishVersion"),
        rule("lambda", "POST",   ".*/functions/[^/]+/invoke-async$",       "lambda:InvokeAsync"),
        rule("lambda", "GET",    ".*/functions/[^/]+/urls$",               "lambda:ListFunctionUrlConfigs"),
        rule("lambda", "GET",    ".*/functions/[^/]+/concurrency$",        "lambda:GetFunctionConcurrency"),
        rule("lambda", "PUT",    ".*/functions/[^/]+/concurrency$",        "lambda:PutFunctionConcurrency"),
        rule("lambda", "DELETE", ".*/functions/[^/]+/concurrency$",        "lambda:DeleteFunctionConcurrency"),
        rule("lambda", "GET",    ".*/functions/[^/]+/provisioned-concurrency$", "List", "ALL",
                "lambda:ListProvisionedConcurrencyConfigs"),
        rule("lambda", "GET",    ".*/functions/[^/]+/provisioned-concurrency$",
                "lambda:GetProvisionedConcurrencyConfig"),
        rule("lambda", "PUT",    ".*/functions/[^/]+/provisioned-concurrency$",
                "lambda:PutProvisionedConcurrencyConfig"),
        rule("lambda", "DELETE", ".*/functions/[^/]+/provisioned-concurrency$",
                "lambda:DeleteProvisionedConcurrencyConfig"),
        rule("lambda", "GET",    ".*/functions/[^/]+/event-invoke-config/list$",
                "lambda:ListFunctionEventInvokeConfigs"),
        rule("lambda", "GET",    ".*/functions/[^/]+/event-invoke-config$", "lambda:GetFunctionEventInvokeConfig"),
        rule("lambda", "PUT",    ".*/functions/[^/]+/event-invoke-config$", "lambda:PutFunctionEventInvokeConfig"),
        rule("lambda", "POST",   ".*/functions/[^/]+/event-invoke-config$", "lambda:UpdateFunctionEventInvokeConfig"),
        rule("lambda", "DELETE", ".*/functions/[^/]+/event-invoke-config$", "lambda:DeleteFunctionEventInvokeConfig"),
        rule("lambda", "GET",    ".*/functions/[^/]+/code-signing-config$", "lambda:GetFunctionCodeSigningConfig"),
        rule("lambda", "PUT",    ".*/functions/[^/]+/code-signing-config$", "lambda:PutFunctionCodeSigningConfig"),
        rule("lambda", "DELETE", ".*/functions/[^/]+/code-signing-config$", "lambda:DeleteFunctionCodeSigningConfig"),
        rule("lambda", "GET",    ".*/functions/[^/]+/recursion-config$",   "lambda:GetFunctionRecursionConfig"),
        rule("lambda", "PUT",    ".*/functions/[^/]+/recursion-config$",   "lambda:PutFunctionRecursionConfig"),
        rule("lambda", "GET",    ".*/functions/[^/]+/function-scaling-config$", "lambda:GetFunctionScalingConfig"),
        rule("lambda", "PUT",    ".*/functions/[^/]+/function-scaling-config$", "lambda:PutFunctionScalingConfig"),
        rule("lambda", "GET",    ".*/functions/[^/]+/runtime-management-config$", "lambda:GetRuntimeManagementConfig"),
        rule("lambda", "PUT",    ".*/functions/[^/]+/runtime-management-config$", "lambda:PutRuntimeManagementConfig"),
        rule("lambda", "GET",    "^/2016-08-19/account-settings/?$",       "lambda:GetAccountSettings"),
        rule("lambda", "GET",    "^/2018-10-31/layers/[^/]+/versions/[^/]+/policy$", "lambda:GetLayerVersionPolicy"),
        rule("lambda", "POST",   "^/2018-10-31/layers/[^/]+/versions/[^/]+/policy$",
                "lambda:AddLayerVersionPermission"),
        rule("lambda", "DELETE", "^/2018-10-31/layers/[^/]+/versions/[^/]+/policy/[^/]+$",
                "lambda:RemoveLayerVersionPermission"),
        rule("lambda", "GET",    "^/2018-10-31/layers/[^/]+/versions/[^/]+$", "lambda:GetLayerVersion"),
        rule("lambda", "DELETE", "^/2018-10-31/layers/[^/]+/versions/[^/]+$", "lambda:DeleteLayerVersion"),
        rule("lambda", "GET",    "^/2018-10-31/layers/[^/]+/versions$",    "lambda:ListLayerVersions"),
        rule("lambda", "POST",   "^/2018-10-31/layers/[^/]+/versions$",    "lambda:PublishLayerVersion"),
        rule("lambda", "GET",    "^/2025-11-30/capacity-providers/[^/]+/function-versions$",
                "lambda:ListFunctionVersionsByCapacityProvider"),
        rule("lambda", "GET",    "^/2025-11-30/capacity-providers/[^/]+$", "lambda:GetCapacityProvider"),
        rule("lambda", "PUT",    "^/2025-11-30/capacity-providers/[^/]+$", "lambda:UpdateCapacityProvider"),
        rule("lambda", "DELETE", "^/2025-11-30/capacity-providers/[^/]+$", "lambda:DeleteCapacityProvider"),
        rule("lambda", "GET",    "^/2025-11-30/capacity-providers$",       "lambda:ListCapacityProviders"),
        rule("lambda", "POST",   "^/2025-11-30/capacity-providers$",       "lambda:CreateCapacityProvider"),
        // The resource policy routes of the function API (2026-07-09) and of Web Functions (2025-03-07).
        rule("lambda", "PUT",    "^/2026-07-09/resource-policy/.+",        "lambda:PutResourcePolicy"),
        rule("lambda", "GET",    "^/2026-07-09/resource-policy/.+",        "lambda:GetResourcePolicy"),
        rule("lambda", "DELETE", "^/2026-07-09/resource-policy/.+",        "lambda:DeleteResourcePolicy"),

        // ── Lambda Web Functions (/2025-03-07, signs as lambda) ─────────────────
        rule("lambda", "PUT",    "^/2025-03-07/web-functions$",                       "lambda:CreateWebFunction"),
        rule("lambda", "POST",   "^/2025-03-07/web-functions$",                       "lambda:ListWebFunctions"),
        rule("lambda", "GET",    "^/2025-03-07/web-functions/[^/]+$",                 "lambda:GetWebFunction"),
        rule("lambda", "DELETE", "^/2025-03-07/web-functions/[^/]+$",                 "lambda:DeleteWebFunction"),
        rule("lambda", "POST",   "^/2025-03-07/web-functions/[^/]+/revisions$",       "lambda:CreateWebFunctionRevision"),
        rule("lambda", "POST",   "^/2025-03-07/web-functions/[^/]+/list-revisions$",  "lambda:ListWebFunctionRevisions"),
        rule("lambda", "GET",    "^/2025-03-07/web-functions/[^/]+/revisions/[^/]+$", "lambda:GetWebFunctionRevision"),
        rule("lambda", "DELETE", "^/2025-03-07/web-functions/[^/]+/revisions/[^/]+$", "lambda:DeleteWebFunctionRevision"),
        rule("lambda", "PUT",    "^/2025-03-07/web-functions/[^/]+/endpoints$",       "lambda:CreateWebFunctionEndpoint"),
        rule("lambda", "POST",   "^/2025-03-07/web-functions/[^/]+/list-endpoints$",  "lambda:ListWebFunctionEndpoints"),
        rule("lambda", "GET",    "^/2025-03-07/web-functions/[^/]+/endpoints/[^/]+$", "lambda:GetWebFunctionEndpoint"),
        rule("lambda", "PATCH",  "^/2025-03-07/web-functions/[^/]+/endpoints/[^/]+$", "lambda:UpdateWebFunctionEndpoint"),
        rule("lambda", "DELETE", "^/2025-03-07/web-functions/[^/]+/endpoints/[^/]+$", "lambda:DeleteWebFunctionEndpoint"),
        rule("lambda", "GET",    "^/2025-03-07/web-account-settings$",                "lambda:GetWebAccountSettings"),
        rule("lambda", "GET",    "^/2025-03-07/tags/.+",                              "lambda:ListTags"),
        rule("lambda", "POST",   "^/2025-03-07/tags/.+",                              "lambda:TagResource"),
        rule("lambda", "DELETE", "^/2025-03-07/tags/.+",                              "lambda:UntagResource"),
        rule("lambda", "PUT",    "^/2025-03-07/resource-policy/.+",                   "lambda:PutResourcePolicy"),
        rule("lambda", "GET",    "^/2025-03-07/resource-policy/.+",                   "lambda:GetResourcePolicy"),
        rule("lambda", "DELETE", "^/2025-03-07/resource-policy/.+",                   "lambda:DeleteResourcePolicy"),
        // InvokeWithResponseStream and GetLayerVersionByArn are authorized as the action they share with
        // Invoke and GetLayerVersion (iam-authorized-actions.json).
        rule("lambda", "POST",   ".*/functions/[^/]+/response-streaming-invocations$", "lambda:InvokeFunction"),
        rule("lambda", "GET",    "^/2018-10-31/layers$", "find", "LayerVersion",     "lambda:GetLayerVersion"),
        rule("lambda", "GET",    "^/2018-10-31/layers$",                              "lambda:ListLayers"),
        // Lambda MicroVMs (2025-09-09) and their tags, and network connectors (2026-04-04).
        rule("lambda", "POST",   "^/2025-09-09/microvms/[^/]+/auth-token$",            "lambda:CreateMicrovmAuthToken"),
        rule("lambda", "POST",   "^/2025-09-09/microvms/[^/]+/shell-auth-token$",      "lambda:CreateMicrovmShellAuthToken"),
        rule("lambda", "POST",   "^/2025-09-09/microvms/[^/]+/resume$",                "lambda:ResumeMicrovm"),
        rule("lambda", "POST",   "^/2025-09-09/microvms/[^/]+/suspend$",               "lambda:SuspendMicrovm"),
        rule("lambda", "GET",    "^/2025-09-09/microvms/[^/]+$",                       "lambda:GetMicrovm"),
        rule("lambda", "DELETE", "^/2025-09-09/microvms/[^/]+$",                       "lambda:TerminateMicrovm"),
        rule("lambda", "GET",    "^/2025-09-09/microvms$",                             "lambda:ListMicrovms"),
        rule("lambda", "POST",   "^/2025-09-09/microvms$",                             "lambda:RunMicrovm"),
        rule("lambda", "GET",    "^/2025-09-09/microvm-images/[^/]+/versions/[^/]+/builds/[^/]+$",
                "lambda:GetMicrovmImageBuild"),
        rule("lambda", "GET",    "^/2025-09-09/microvm-images/[^/]+/versions/[^/]+/builds$",
                "lambda:ListMicrovmImageBuilds"),
        rule("lambda", "GET",    "^/2025-09-09/microvm-images/[^/]+/versions/[^/]+$",  "lambda:GetMicrovmImageVersion"),
        rule("lambda", "PATCH",  "^/2025-09-09/microvm-images/[^/]+/versions/[^/]+$",  "lambda:UpdateMicrovmImageVersion"),
        rule("lambda", "DELETE", "^/2025-09-09/microvm-images/[^/]+/versions/[^/]+$",  "lambda:DeleteMicrovmImageVersion"),
        rule("lambda", "GET",    "^/2025-09-09/microvm-images/[^/]+/versions$",        "lambda:ListMicrovmImageVersions"),
        rule("lambda", "GET",    "^/2025-09-09/microvm-images/[^/]+$",                 "lambda:GetMicrovmImage"),
        rule("lambda", "PUT",    "^/2025-09-09/microvm-images/[^/]+$",                 "lambda:UpdateMicrovmImage"),
        rule("lambda", "DELETE", "^/2025-09-09/microvm-images/[^/]+$",                 "lambda:DeleteMicrovmImage"),
        rule("lambda", "GET",    "^/2025-09-09/microvm-images$",                       "lambda:ListMicrovmImages"),
        rule("lambda", "POST",   "^/2025-09-09/microvm-images$",                       "lambda:CreateMicrovmImage"),
        rule("lambda", "GET",    "^/2025-09-09/managed-microvm-images/[^/]+/versions$",
                "lambda:ListManagedMicrovmImageVersions"),
        rule("lambda", "GET",    "^/2025-09-09/managed-microvm-images$",               "lambda:ListManagedMicrovmImages"),
        rule("lambda", "GET",    "^/2017-03-31/tags/.+",                               "lambda:ListTags"),
        rule("lambda", "POST",   "^/2017-03-31/tags/.+",                               "lambda:TagResource"),
        rule("lambda", "DELETE", "^/2017-03-31/tags/.+",                               "lambda:UntagResource"),
        rule("lambda", "GET",    "^/2026-04-04/network-connectors/[^/]+/versions$",    "lambda:ListNetworkConnectorVersions"),
        rule("lambda", "GET",    "^/2026-04-04/network-connectors/[^/]+$",             "lambda:GetNetworkConnector"),
        rule("lambda", "PUT",    "^/2026-04-04/network-connectors/[^/]+$",             "lambda:UpdateNetworkConnector"),
        rule("lambda", "DELETE", "^/2026-04-04/network-connectors/[^/]+$",             "lambda:DeleteNetworkConnector"),
        rule("lambda", "GET",    "^/2026-04-04/network-connectors$",                   "lambda:ListNetworkConnectors"),
        rule("lambda", "POST",   "^/2026-04-04/network-connectors$",                   "lambda:CreateNetworkConnector"),

        // ── DynamoDB (JSON 1.1, action from X-Amz-Target handled separately) ──
        // Handled via Query-style action extraction in the filter

        // ── RDS Data API ───────────────────────────────────────────────────────
        rule("rds-data", "POST", "^/Execute/?$",              "rds-data:ExecuteStatement"),
        rule("rds-data", "POST", "^/ExecuteSql/?$",           "rds-data:ExecuteSql"),
        rule("rds-data", "POST", "^/BatchExecute/?$",         "rds-data:BatchExecuteStatement"),
        rule("rds-data", "POST", "^/BeginTransaction/?$",     "rds-data:BeginTransaction"),
        rule("rds-data", "POST", "^/CommitTransaction/?$",    "rds-data:CommitTransaction"),
        rule("rds-data", "POST", "^/RollbackTransaction/?$",  "rds-data:RollbackTransaction"),

        // ── EKS ────────────────────────────────────────────────────────────────
        // Floci takes an access entry's principal ARN whole, slashes included, so any other GET or DELETE under
        // an entry is DescribeAccessEntry or DeleteAccessEntry. The entry's access policy routes come first, as
        // they win in the router; a DELETE is a disassociate only when a cluster access policy ARN follows.
        rule("eks", "GET",    "^/clusters/[^/]+/access-entries/.+/access-policies$",     "eks:ListAssociatedAccessPolicies"),
        rule("eks", "POST",   "^/clusters/[^/]+/access-entries/.+/access-policies$",     "eks:AssociateAccessPolicy"),
        rule("eks", "DELETE", "^/clusters/[^/]+/access-entries/.+/access-policies/" + EksController.ACCESS_POLICY_ARN + "$",
                "eks:DisassociateAccessPolicy"),
        rule("eks", "GET",    "^/clusters/[^/]+/access-entries/.+$",                     "eks:DescribeAccessEntry"),
        rule("eks", "DELETE", "^/clusters/[^/]+/access-entries/.+$",                     "eks:DeleteAccessEntry"),
        rule("eks", "POST",   "^/clusters/[^/]+/access-entries/.+$",                     "eks:UpdateAccessEntry"),
        rule("eks", "GET",    "^/clusters/[^/]+/access-entries$",                        "eks:ListAccessEntries"),
        rule("eks", "POST",   "^/clusters/[^/]+/access-entries$",                        "eks:CreateAccessEntry"),
        rule("eks", "GET",    "^/clusters$",                                             "eks:ListClusters"),
        rule("eks", "POST",   "^/clusters$",                                             "eks:CreateCluster"),
        rule("eks", "GET",    "^/clusters/[^/]+$",                                       "eks:DescribeCluster"),
        rule("eks", "DELETE", "^/clusters/[^/]+$",                                       "eks:DeleteCluster"),
        rule("eks", "POST",   "^/clusters/[^/]+/update-config$",                         "eks:UpdateClusterConfig"),
        rule("eks", "POST",   "^/clusters/[^/]+/encryption-config/associate$", "eks:AssociateEncryptionConfig"),
        rule("eks", "GET",    "^/clusters/[^/]+/updates$",                               "eks:ListUpdates"),
        rule("eks", "POST",   "^/clusters/[^/]+/updates$",                               "eks:UpdateClusterVersion"),
        rule("eks", "GET",    "^/clusters/[^/]+/updates/[^/]+$",                         "eks:DescribeUpdate"),
        rule("eks", "POST",   "^/clusters/[^/]+/updates/[^/]+/cancel-update$",           "eks:CancelUpdate"),
        rule("eks", "GET",    "^/clusters/[^/]+/node-groups$",                           "eks:ListNodegroups"),
        rule("eks", "POST",   "^/clusters/[^/]+/node-groups$",                           "eks:CreateNodegroup"),
        rule("eks", "GET",    "^/clusters/[^/]+/node-groups/[^/]+$",                     "eks:DescribeNodegroup"),
        rule("eks", "DELETE", "^/clusters/[^/]+/node-groups/[^/]+$",                     "eks:DeleteNodegroup"),
        rule("eks", "POST",   "^/clusters/[^/]+/node-groups/[^/]+/update-config$",       "eks:UpdateNodegroupConfig"),
        rule("eks", "POST",   "^/clusters/[^/]+/node-groups/[^/]+/update-version$",      "eks:UpdateNodegroupVersion"),
        rule("eks", "GET",    "^/clusters/[^/]+/fargate-profiles$",                      "eks:ListFargateProfiles"),
        rule("eks", "POST",   "^/clusters/[^/]+/fargate-profiles$",                      "eks:CreateFargateProfile"),
        rule("eks", "GET",    "^/clusters/[^/]+/fargate-profiles/[^/]+$",                "eks:DescribeFargateProfile"),
        rule("eks", "DELETE", "^/clusters/[^/]+/fargate-profiles/[^/]+$",                "eks:DeleteFargateProfile"),
        rule("eks", "GET",    "^/clusters/[^/]+/addons$",                                "eks:ListAddons"),
        rule("eks", "POST",   "^/clusters/[^/]+/addons$",                                "eks:CreateAddon"),
        rule("eks", "GET",    "^/clusters/[^/]+/addons/[^/]+$",                          "eks:DescribeAddon"),
        rule("eks", "DELETE", "^/clusters/[^/]+/addons/[^/]+$",                          "eks:DeleteAddon"),
        rule("eks", "POST",   "^/clusters/[^/]+/addons/[^/]+/update$",                   "eks:UpdateAddon"),
        rule("eks", "GET",    "^/addons/supported-versions$",                            "eks:DescribeAddonVersions"),
        rule("eks", "GET",    "^/addons/configuration-schemas$", "eks:DescribeAddonConfiguration"),
        rule("eks", "GET",    "^/clusters/[^/]+/pod-identity-associations$", "eks:ListPodIdentityAssociations"),
        rule("eks", "POST",   "^/clusters/[^/]+/pod-identity-associations$", "eks:CreatePodIdentityAssociation"),
        rule("eks", "GET",    "^/clusters/[^/]+/pod-identity-associations/[^/]+$",
                "eks:DescribePodIdentityAssociation"),
        rule("eks", "POST",   "^/clusters/[^/]+/pod-identity-associations/[^/]+$",
                "eks:UpdatePodIdentityAssociation"),
        rule("eks", "DELETE", "^/clusters/[^/]+/pod-identity-associations/[^/]+$",
                "eks:DeletePodIdentityAssociation"),
        rule("eks", "GET",    "^/clusters/[^/]+/identity-provider-configs$", "eks:ListIdentityProviderConfigs"),
        rule("eks", "POST",   "^/clusters/[^/]+/identity-provider-configs/associate$",
                "eks:AssociateIdentityProviderConfig"),
        rule("eks", "POST",   "^/clusters/[^/]+/identity-provider-configs/describe$",
                "eks:DescribeIdentityProviderConfig"),
        rule("eks", "POST",   "^/clusters/[^/]+/identity-provider-configs/disassociate$",
                "eks:DisassociateIdentityProviderConfig"),
        rule("eks", "POST",   "^/clusters/[^/]+/insights$",                              "eks:ListInsights"),
        rule("eks", "GET",    "^/clusters/[^/]+/insights/[^/]+$",                        "eks:DescribeInsight"),
        rule("eks", "GET",    "^/clusters/[^/]+/insights-refresh$",                      "eks:DescribeInsightsRefresh"),
        rule("eks", "POST",   "^/clusters/[^/]+/insights-refresh$",                      "eks:StartInsightsRefresh"),
        rule("eks", "GET",    "^/clusters/[^/]+/capabilities$",                          "eks:ListCapabilities"),
        rule("eks", "POST",   "^/clusters/[^/]+/capabilities$",                          "eks:CreateCapability"),
        rule("eks", "GET",    "^/clusters/[^/]+/capabilities/[^/]+$",                    "eks:DescribeCapability"),
        rule("eks", "POST",   "^/clusters/[^/]+/capabilities/[^/]+$",                    "eks:UpdateCapability"),
        rule("eks", "DELETE", "^/clusters/[^/]+/capabilities/[^/]+$",                    "eks:DeleteCapability"),
        rule("eks", "GET",    "^/clusters/[^/]+/certificate-authorities$", "eks:ListCertificateAuthorities"),
        rule("eks", "POST",   "^/clusters/[^/]+/certificate-authorities$", "eks:CreateCertificateAuthority"),
        rule("eks", "GET",    "^/clusters/[^/]+/certificate-authorities/[^/]+$",
                "eks:DescribeCertificateAuthority"),
        rule("eks", "DELETE", "^/clusters/[^/]+/certificate-authorities/[^/]+$",
                "eks:DeleteCertificateAuthority"),
        rule("eks", "POST",   "^/clusters/[^/]+/certificate-authorities/[^/]+/activate$",
                "eks:ActivateCertificateAuthority"),
        rule("eks", "GET",    "^/access-policies$",                                      "eks:ListAccessPolicies"),
        rule("eks", "GET",    "^/cluster-versions$",                                     "eks:DescribeClusterVersions"),
        rule("eks", "POST",   "^/cluster-registrations$",                                "eks:RegisterCluster"),
        rule("eks", "DELETE", "^/cluster-registrations/[^/]+$",                          "eks:DeregisterCluster"),
        rule("eks", "GET",    "^/eks-anywhere-subscriptions$", "eks:ListEksAnywhereSubscriptions"),
        rule("eks", "POST",   "^/eks-anywhere-subscriptions$", "eks:CreateEksAnywhereSubscription"),
        rule("eks", "GET",    "^/eks-anywhere-subscriptions/[^/]+$", "eks:DescribeEksAnywhereSubscription"),
        rule("eks", "POST",   "^/eks-anywhere-subscriptions/[^/]+$", "eks:UpdateEksAnywhereSubscription"),
        rule("eks", "DELETE", "^/eks-anywhere-subscriptions/[^/]+$", "eks:DeleteEksAnywhereSubscription"),
        rule("eks", "GET",    "^/tags(/.+)?$",                                           "eks:ListTagsForResource"),
        rule("eks", "POST",   "^/tags(/.+)?$",                                           "eks:TagResource"),
        rule("eks", "DELETE", "^/tags(/.+)?$",                                           "eks:UntagResource"),

        // ── Resource Groups ─────────────────────────────────────────────────────
        rule("resource-groups", "POST", "^/_rg/groups/?$", "resource-groups:CreateGroup"),
        rule("resource-groups", "POST", "^/_rg/get-group/?$", "resource-groups:GetGroup"),
        rule("resource-groups", "POST", "^/_rg/delete-group/?$", "resource-groups:DeleteGroup"),
        rule("resource-groups", "POST", "^/_rg/group-resources/?$", "resource-groups:GroupResources"),
        rule("resource-groups", "POST", "^/_rg/list-group-resources/?$", "resource-groups:ListGroupResources"),

        // ── Kinesis ────────────────────────────────────────────────────────────
        rule("kinesis", "POST", ".*", "kinesis:*")
    );

    private static ActionRule rule(String service, String method, String path, String action) {
        return rule(service, method, path, null, null, action);
    }

    private static ActionRule rule(String service, String method, String path, String queryParameter,
                                   String queryValue, String action) {
        return new ActionRule(service, method, Pattern.compile(path, Pattern.CASE_INSENSITIVE), queryParameter,
                queryValue, action);
    }

    /**
     * Whether the request is an S3 Control one: the route it matches is the {@code s3control} model's. A denial then
     * answers in S3 Control's own REST-XML envelope, the only one the s3control SDK deserializers read (issue #557),
     * so the route decides the shape and not the action. A request whose route cannot be read, which only a synthetic
     * context is, is not a control one.
     */
    public static boolean isS3ControlRequest(String credentialScope, ContainerRequestContext ctx) {
        if (!"s3".equals(credentialScope) || ctx.getMethod() == null || ctx.getUriInfo() == null
                || ctx.getUriInfo().getRequestUri() == null) {
            return false;
        }
        return matchedRoute(IamRestRoutes.vendored(), credentialScope, ctx)
                .map(match -> "s3control".equals(match.model())).orElse(false);
    }

    /**
     * Resolves the IAM action for an incoming request.
     *
     * For Query-protocol requests the action comes from {@code Action} or
     * {@code Operation} (e.g. {@code sqs:SendMessage}). REST requests never
     * read these caller-controlled fields as an IAM action.
     *
     * For JSON 1.1 protocol the action comes from {@code X-Amz-Target}
     * (e.g. {@code DynamoDB_20120810.PutItem} → {@code dynamodb:PutItem}).
     *
     * For REST-JSON services the action is derived from the path rule table, or else from the
     * operation whose botocore route the request matches.
     *
     * Returns {@code null} when the action is unknown (caller treats this as ALLOW).
     */
    public String resolve(String credentialScope, ContainerRequestContext ctx) {
        // REST requests with Action or Operation fields still use their method and path rules.
        if (isQueryRequest(ctx)) {
            String queryAction = queryAction(ctx);
            if (queryAction != null && !queryAction.isBlank()) {
                return IamAuthorizedActions.actionFor(credentialScope, queryAction);
            }
        }

        // JSON 1.1: X-Amz-Target → the action AWS checks for the operation it names
        String operationName = targetOperation(ctx);
        if (operationName != null) {
            return resolveOperation(credentialScope, operationName, ctx);
        }

        return resolveRoute(credentialScope, ctx);
    }

    /** The operation a JSON request's {@code X-Amz-Target} names, the part after its last dot; null without one. */
    public static String targetOperation(ContainerRequestContext ctx) {
        String target = ctx.getHeaderString("X-Amz-Target");
        return target != null && target.contains(".") ? target.substring(target.lastIndexOf('.') + 1) : null;
    }

    /**
     * The action for a dispatched operation. One AWS authorizes by what its request's parts each do (a DynamoDB
     * PartiQL statement by its verb) is checked as the actions the request-resources table gives those parts.
     */
    public String resolveOperation(String credentialScope, String operationName, ContainerRequestContext ctx) {
        return IamAuthorizedActions.actionFor(credentialScope, operationName);
    }

    /** The serving method identifies these actions even when path encoding or matrix parameters vary. */
    public String resolveMatchedRoute(String credentialScope, ResourceInfo resourceInfo) {
        if (resourceInfo == null || resourceInfo.getResourceClass() == null) {
            return null;
        }
        IamOperation iamOperation = resourceInfo.getResourceClass().getAnnotation(IamOperation.class);
        if (iamOperation == null || !credentialScope.equals(iamOperation.namespace())) {
            return null;
        }
        return IamAuthorizedActions.actionFor(iamOperation.namespace(), iamOperation.operation());
    }

    /**
     * The action of a REST request, from its method and path alone. A REST request is the operation
     * whose route it reached, so neither an {@code X-Amz-Target} header nor a Query {@code Action}
     * field may name another one: a caller could attach either to have the call checked as an
     * action they hold. The path is matched as sent, still percent-encoded, which is the form the
     * router matched: decoded, an encoded {@code /} inside a parameter splits it into two segments,
     * so the route's rule would not match and the call would pass as an unknown action. It is read
     * from the request URI because RESTEasy Reactive does not serve {@code UriInfo.getPath(false)}.
     *
     * <p>A request no rule matches is the operation whose route, in botocore's model of its service, it
     * matches, and is checked as that operation's action for the request's method
     * ({@link IamAuthorizedActions#actionFor(String, String, String)}): the one a request naming the operation
     * is checked as, or, for an operation AWS authorizes by method as it does API Gateway's management API,
     * {@code apigateway:PUT} for a PUT. That is when Floci can check it as AWS does ({@code checkedAsAws}). One
     * it cannot is left unmapped rather than checked on {@code *}, which would refuse a grant scoped to the
     * resource AWS checks it on. A request a route filter moved under an internal prefix is matched on its
     * path without the prefix ({@link #withoutInternalPrefix}), the path the model gives it. Returns {@code null}
     * when nothing maps the request (caller treats this as ALLOW).
     */
    /**
     * The scope a request Floci serves under {@code credentialScope} is checked under: the signing name AWS signs its
     * model's requests with, where the scope is a vendored model's own name the routes file does not route
     * ({@link IamRestRoutes#signedScope}), and the scope itself otherwise.
     */
    public String routedScope(String credentialScope) {
        return restRoutes.signedScope(credentialScope);
    }

    public String resolveRoute(String credentialScope, ContainerRequestContext ctx) {
        String method = ctx.getMethod().toUpperCase();
        // Matched on the path the router dispatched on, as every scope is: S3 too, whose key of slashes alone
        // S3SlashOnlyKeyFilter rewrites escaped (PUT /bucket// writes the key "/"), and whose own reading of a key,
        // which keeps the slashes it was sent with, names the object (RequestPathLabels).
        String path = routedPath(ctx);

        for (ActionRule rule : RULES) {
            if (rule.matches(credentialScope, method, path, ctx)) {
                return rule.action();
            }
        }
        MultivaluedMap<String, String> query = ctx.getUriInfo() == null ? null : ctx.getUriInfo().getQueryParameters();
        Optional<IamRestRoutes.Match> match = restRoutes.match(credentialScope, method, withoutInternalPrefix(ctx, path),
                query, movedUnderPrefix(ctx), ctx.getHeaders());
        if (match.isPresent()) {
            String action = IamAuthorizedActions.actionFor(credentialScope, match.get().operation(), method,
                    match.get().model(), query);
            if (checkedAsAws(credentialScope, match.get().operation(), action)) {
                return action;
            }
        }

        LOG.debugv("No action mapping for {0} {1} {2} — defaulting to ALLOW", credentialScope, method, path);
        return null;
    }

    /** A REST model's explicit denial status, retaining the protocol default when none is modelled. */
    public static int accessDeniedStatus(String credentialScope, int fallback) {
        return IamRestRoutes.vendored().accessDeniedStatus(credentialScope, fallback);
    }

    /**
     * The value a REST request's path gives each label of the vendored route it matches, decoded, by the label's
     * name; empty when it matches none. {@link RequestResources} reads a member the table of request resources
     * locates in the path from them, as {@link #resolveRoute} matches the route.
     */
    public static Map<String, String> pathLabels(String credentialScope, ContainerRequestContext ctx) {
        IamRestRoutes routes = IamRestRoutes.vendored();
        if (credentialScope == null || !routes.routes(credentialScope)) {
            return Map.of();
        }
        return matchedRoute(routes, credentialScope, ctx).map(IamRestRoutes.Match::labels).orElse(Map.of());
    }

    /**
     * Whether a request of the credential scope is taken by a route of a model AWS documents on a page of its own
     * rather than in botocore, as Glue's Iceberg REST endpoint is: such a request is another API's, Apache Iceberg's
     * REST catalog, so it carries none of its operation's members, and one its body gives was added by the caller.
     */
    public static boolean takenByDocumentedRoute(String credentialScope, ContainerRequestContext ctx) {
        IamRestRoutes routes = IamRestRoutes.vendored();
        return credentialScope != null && routes.documents(credentialScope)
                && matchedRoute(routes, credentialScope, ctx).map(IamRestRoutes.Match::documented).orElse(false);
    }

    /**
     * The vendored route of the credential scope a request's method, path and query match, as {@link #resolveRoute}
     * matches it.
     */
    private static Optional<IamRestRoutes.Match> matchedRoute(IamRestRoutes routes, String credentialScope,
                                                              ContainerRequestContext ctx) {
        return routes.match(credentialScope, ctx.getMethod().toUpperCase(), withoutInternalPrefix(ctx, routedPath(ctx)),
                ctx.getUriInfo() == null ? null : ctx.getUriInfo().getQueryParameters(), movedUnderPrefix(ctx),
                ctx.getHeaders());
    }

    /**
     * Whether the path of a request sent to the URI {@code uriInfo} gives is one of the credential scope's vendored
     * model, whatever method it is sent with: matched still percent-encoded, so an encoded {@code /} stays inside
     * its label and an unencoded one ends it. True for a scope whose routes are not vendored, which the model
     * cannot answer for.
     */
    public static boolean modelRoutes(String credentialScope, UriInfo uriInfo) {
        IamRestRoutes routes = IamRestRoutes.vendored();
        return !routes.routes(credentialScope)
                || routes.takesPath(credentialScope, sentPath(uriInfo), uriInfo.getQueryParameters());
    }

    /** Keep the modeled operation when AWS authorizes its route under a different action name. */
    public String operationForRoute(String credentialScope, String action, ContainerRequestContext ctx) {
        if (credentialScope == null || action == null) {
            return action;
        }
        String method = ctx.getMethod().toUpperCase();
        MultivaluedMap<String, String> query = ctx.getUriInfo() == null ? null : ctx.getUriInfo().getQueryParameters();
        return restRoutes.match(credentialScope, method, withoutInternalPrefix(ctx, routedPath(ctx)), query,
                        movedUnderPrefix(ctx), ctx.getHeaders())
                .filter(match -> action.equals(IamAuthorizedActions.actionFor(credentialScope, match.operation(),
                        method, match.model(), query)))
                .map(match -> credentialScope + ":" + match.operation())
                .orElse(action);
    }

    /**
     * The vendored route a request of an operation matched: the member locations of a generated alternative route
     * ({@code located}; absent entries keep the operation's model locations) and the botocore model whose route it is
     * ({@code model}), which tells apart the clients of an operation several serve, with that model's API version
     * ({@code apiVersion}), which the rows of the generated table read for its API alone name.
     */
    public record RouteMembers(Map<String, String> located, String model, String apiVersion) {
        static final RouteMembers NONE = new RouteMembers(Map.of(), null, null);
    }

    /**
     * Whether a REST request of the credential scope is taken alike by the routes of two operations, none outranking
     * the other, as one naming two of S3's sub-resources is ({@code PUT /bucket?cors&policy}): no botocore client
     * sends one, and it runs as whichever its handler picks, so no one action covers it.
     */
    public static boolean ambiguousRoute(String credentialScope, ContainerRequestContext ctx) {
        if (credentialScope == null || ctx.getMethod() == null || ctx.getUriInfo() == null
                || ctx.getUriInfo().getRequestUri() == null) {
            return false;
        }
        String path = routedPath(ctx);
        return IamRestRoutes.vendored().ambiguous(credentialScope, ctx.getMethod().toUpperCase(),
                withoutInternalPrefix(ctx, path), ctx.getUriInfo().getQueryParameters(), movedUnderPrefix(ctx),
                ctx.getHeaders());
    }

    /** The route a request of the operation matches, {@link RouteMembers#NONE} for one it matches none of. */
    public static RouteMembers routeMembers(String operation, ContainerRequestContext ctx) {
        int separator = operation.indexOf(':');
        if (separator < 0 || ctx.getMethod() == null || ctx.getUriInfo() == null
                || ctx.getUriInfo().getRequestUri() == null) {
            return RouteMembers.NONE;
        }
        return IamRestRoutes.vendored().match(operation.substring(0, separator), ctx.getMethod().toUpperCase(),
                        withoutInternalPrefix(ctx, routedPath(ctx)), ctx.getUriInfo().getQueryParameters(),
                        movedUnderPrefix(ctx), ctx.getHeaders())
                .filter(match -> match.operation().equals(operation.substring(separator + 1)))
                .map(match -> new RouteMembers(match.located(), match.model(),
                        IamRestRoutes.vendored().apiVersion(match.model())))
                .orElse(RouteMembers.NONE);
    }

    /**
     * Whether a request of the operation is checked on the resources AWS checks its action on: those its request
     * names, which the vendored request resources read for the operation ({@link RequestResources#reads}), or
     * {@code *} when AWS authorizes the action on no resource type ({@link IamAuthorizedActions#actsOnNoResourceType}),
     * so none of the resources in the request's path is needed.
     */
    private static boolean checkedAsAws(String credentialScope, String operation, String action) {
        return RequestResources.reads(credentialScope + ":" + operation)
                || IamAuthorizedActions.actsOnNoResourceType(action);
    }

    /**
     * The path the router matched, relative to the application root. A request no filter rewrote is matched
     * on its normalized path, still percent-encoded but with encoded unreserved characters such as {@code %2E}
     * decoded and dot segments removed, so {@code /functions/x/%2E%2E/fn} is {@code /functions/fn}; a request
     * a filter rewrote is matched on its new path, decoded. {@link #sentPath} for a synthetic context.
     */
    static String rawPath(ContainerRequestContext ctx) {
        ResteasyReactiveRequestContext request = ResteasyRequests.of(ctx);
        return request == null ? sentPath(ctx.getUriInfo()) : rooted(request.getPathWithoutPrefix());
    }

    /**
     * The request path relative to the application root, as {@link UriInfo#getPath()} gives it, but
     * still percent-encoded.
     */
    static String sentPath(UriInfo uriInfo) {
        String path = uriInfo.getRequestUri().getRawPath();
        String base = uriInfo.getBaseUri().getRawPath();
        if (base != null && base.length() > 1 && path.startsWith(base)) {
            path = path.substring(base.length() - 1);
        }
        return rooted(path);
    }

    /**
     * {@link #rawPath} decoded: the path {@link UriInfo#getPath()} would give for the route the router
     * matched. {@link UriInfo#getPath()} itself for a synthetic context.
     */
    public static String decodedPath(ContainerRequestContext ctx) {
        ResteasyReactiveRequestContext request = ResteasyRequests.of(ctx);
        if (request == null) {
            return ctx.getUriInfo().getPath();
        }
        String path = request.getPathWithoutPrefix();
        boolean rewritten = !request.getPath().equals(request.serverRequest().getRequestNormalisedPath());
        return rooted(rewritten ? path : URLDecoder.decode(path.replace("+", "%2B"), StandardCharsets.UTF_8));
    }

    private static String rooted(String path) {
        return path.startsWith("/") ? path : "/" + path;
    }

    /**
     * {@link #rawPath} without a trailing slash: the router matches {@code /clusters/} as {@code /clusters},
     * so a rule ending at the route's last segment has to match it too, or the request would pass as an
     * unknown action.
     */
    static String routedPath(ContainerRequestContext ctx) {
        return withoutTrailingSlash(rawPath(ctx));
    }

    /**
     * The path without the internal prefix a route filter moved the request under ({@link SignedServiceRoute}), which
     * is the path the service's model gives the operation and the vendored routes hold; the path itself when it was
     * moved under none.
     */
    static String withoutInternalPrefix(ContainerRequestContext ctx, String path) {
        return SignedServiceRoute.internalPrefix(ctx)
                .filter(prefix -> path.equals(prefix) || path.startsWith(prefix + "/"))
                .map(prefix -> path.length() == prefix.length() ? "/" : path.substring(prefix.length()))
                .orElse(path);
    }

    /** Whether a route filter moved the request under an internal prefix, so the router matched its path decoded. */
    private static boolean movedUnderPrefix(ContainerRequestContext ctx) {
        return SignedServiceRoute.internalPrefix(ctx).isPresent();
    }

    /** The path without its trailing slashes, the root's own kept. */
    static String withoutTrailingSlash(String path) {
        String trimmed = path;
        while (trimmed.length() > 1 && trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    /**
     * Returns the Query action from GET query parameters or the POST form body, preserving the
     * entity stream. Both use the controller's Action-first, Operation-second rule; a POST URL
     * Action must not override the form operation that will execute.
     */
    public String queryAction(ContainerRequestContext ctx) {
        if ("GET".equalsIgnoreCase(ctx.getMethod())) {
            return AwsQueryServiceResolver.action(ctx.getUriInfo().getQueryParameters());
        }
        return AwsQueryServiceResolver.action(
                RequestBodyReader.formField(ctx, "Action"),
                RequestBodyReader.formField(ctx, "Operation"));
    }

    /** Returns the API version from the same Query input that the controller dispatches. */
    public String queryVersion(ContainerRequestContext ctx) {
        if ("GET".equalsIgnoreCase(ctx.getMethod())) {
            return ctx.getUriInfo().getQueryParameters().getFirst("Version");
        }
        return RequestBodyReader.formField(ctx, "Version");
    }

    /** The root form POST or CloudWatch GET that the Query controller dispatches. */
    public static boolean isQueryRequest(ContainerRequestContext ctx) {
        if ("GET".equalsIgnoreCase(ctx.getMethod())) {
            return ctx.getProperty(AwsProtocolClaimFilter.CLAIM_PROPERTY) instanceof ProtocolClaim claim
                    && claim.protocol() == WireProtocol.AWS_QUERY;
        }
        if (ctx.getUriInfo() == null) {
            return false;
        }
        String path = ctx.getUriInfo().getPath();
        if (path != null && !path.isEmpty() && !"/".equals(path)) {
            return false;
        }
        MediaType mediaType = ctx.getMediaType();
        return "POST".equalsIgnoreCase(ctx.getMethod())
                && mediaType != null
                && "application".equalsIgnoreCase(mediaType.getType())
                && "x-www-form-urlencoded".equalsIgnoreCase(mediaType.getSubtype());
    }
}
