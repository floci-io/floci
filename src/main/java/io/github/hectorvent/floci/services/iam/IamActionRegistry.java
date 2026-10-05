package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.AwsQueryServiceResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Maps (credentialScope, httpMethod, requestPath) → IAM action string.
 *
 * For Query-protocol services (SQS, SNS, IAM, STS, ...) the Action form
 * parameter, or the controller's Operation fallback, is mapped directly to
 * {@code <service>:<Action>}.
 *
 * For REST-JSON services the first matching rule wins (specific before wildcard).
 */
@ApplicationScoped
public class IamActionRegistry {

    private static final Logger LOG = Logger.getLogger(IamActionRegistry.class);

    private record ActionRule(String service, String method, Pattern pathPattern, String action) {}

    private static final List<ActionRule> RULES = List.of(
        // ── S3 ─────────────────────────────────────────────────────────────────
        rule("s3", "GET",    "^/?$",                              "s3:ListAllMyBuckets"),
        rule("s3", "PUT",    "^/[^/]+/?$",                       "s3:CreateBucket"),
        rule("s3", "DELETE", "^/[^/]+/?$",                       "s3:DeleteBucket"),
        rule("s3", "HEAD",   "^/[^/]+/?$",                       "s3:ListBucket"),
        rule("s3", "GET",    "^/[^/]+/?$",                       "s3:ListBucket"),
        rule("s3", "GET",    "^/[^/]+/.+",                       "s3:GetObject"),
        rule("s3", "PUT",    "^/[^/]+/.+",                       "s3:PutObject"),
        rule("s3", "DELETE", "^/[^/]+/.+",                       "s3:DeleteObject"),
        rule("s3", "HEAD",   "^/[^/]+/.+",                       "s3:GetObject"),

        // ── Lambda ──────────────────────────────────────────────────────────────
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

        // ── DynamoDB (JSON 1.1, action from X-Amz-Target handled separately) ──
        // Handled via Query-style action extraction in the filter

        // ── RDS Data API ───────────────────────────────────────────────────────
        rule("rds-data", "POST", "^/Execute/?$",              "rds-data:ExecuteStatement"),
        rule("rds-data", "POST", "^/ExecuteSql/?$",           "rds-data:ExecuteSql"),
        rule("rds-data", "POST", "^/BatchExecute/?$",         "rds-data:BatchExecuteStatement"),
        rule("rds-data", "POST", "^/BeginTransaction/?$",     "rds-data:BeginTransaction"),
        rule("rds-data", "POST", "^/CommitTransaction/?$",    "rds-data:CommitTransaction"),
        rule("rds-data", "POST", "^/RollbackTransaction/?$",  "rds-data:RollbackTransaction"),

        // ── API Gateway ────────────────────────────────────────────────────────
        rule("apigateway", "GET",    ".*/account$",                       "apigateway:GET"),
        rule("apigateway", "PATCH",  ".*/account$",                       "apigateway:PATCH"),
        rule("apigateway", "GET",    ".*/restapis$",                        "apigateway:GET"),
        rule("apigateway", "POST",   ".*/restapis$",                        "apigateway:POST"),
        rule("apigateway", "GET",    ".*/restapis/.+",                      "apigateway:GET"),
        rule("apigateway", "PUT",    ".*/restapis/.+",                      "apigateway:PUT"),
        rule("apigateway", "PATCH",  ".*/restapis/.+",                      "apigateway:PATCH"),
        rule("apigateway", "DELETE", ".*/restapis/.+",                      "apigateway:DELETE"),
        rule("apigateway", "POST",   ".*/restapis/.+",                      "apigateway:POST"),

        // ── SES v2 ─────────────────────────────────────────────────────────────
        // SES v1 is a Query API and resolves from its Action like any other; v2 is REST-JSON under
        // /v2/email, so every operation needs its own rule. One rule per operation in the SES v2
        // model, each mapped to the IAM action AWS's service reference lists for it: always
        // ses:<Operation>, in the ses namespace SES v2 shares with v1. SendEmail has no entry there;
        // the Service Authorization Reference's SES v2 page defines ses:SendEmail as v2's only send
        // action whatever the content (ses:SendRawEmail belongs to v1), so it takes its name too.
        // ListConfigurationSets and ListEmailIdentities keep their GET routes next to the POST ones
        // the model moved them to, since older SDKs, and Floci's own routes, still use them.
        rule("ses", "GET",   "^/v2/email/account/?$",                                                "ses:GetAccount"),
        rule("ses", "PUT",   "^/v2/email/account/dedicated-ips/warmup/?$",                           "ses:PutAccountDedicatedIpWarmupAttributes"),
        rule("ses", "POST",  "^/v2/email/account/details/?$",                                        "ses:PutAccountDetails"),
        rule("ses", "PUT",   "^/v2/email/account/pricing-attributes/?$",                             "ses:PutAccountPricingAttributes"),
        rule("ses", "PUT",   "^/v2/email/account/sending/?$",                                        "ses:PutAccountSendingAttributes"),
        rule("ses", "PUT",   "^/v2/email/account/suppression/?$",                                    "ses:PutAccountSuppressionAttributes"),
        rule("ses", "PUT",   "^/v2/email/account/vdm/?$",                                            "ses:PutAccountVdmAttributes"),
        rule("ses", "GET",   "^/v2/email/configuration-sets/?$",                                     "ses:ListConfigurationSets"),
        rule("ses", "POST",  "^/v2/email/configuration-sets/?$",                                     "ses:CreateConfigurationSet"),
        rule("ses", "DELETE","^/v2/email/configuration-sets/[^/]+/?$",                               "ses:DeleteConfigurationSet"),
        rule("ses", "GET",   "^/v2/email/configuration-sets/[^/]+/?$",                               "ses:GetConfigurationSet"),
        rule("ses", "PUT",   "^/v2/email/configuration-sets/[^/]+/archiving-options/?$",             "ses:PutConfigurationSetArchivingOptions"),
        rule("ses", "PUT",   "^/v2/email/configuration-sets/[^/]+/delivery-options/?$",              "ses:PutConfigurationSetDeliveryOptions"),
        rule("ses", "GET",   "^/v2/email/configuration-sets/[^/]+/event-destinations/?$",            "ses:GetConfigurationSetEventDestinations"),
        rule("ses", "POST",  "^/v2/email/configuration-sets/[^/]+/event-destinations/?$",            "ses:CreateConfigurationSetEventDestination"),
        rule("ses", "DELETE","^/v2/email/configuration-sets/[^/]+/event-destinations/[^/]+/?$",      "ses:DeleteConfigurationSetEventDestination"),
        rule("ses", "PUT",   "^/v2/email/configuration-sets/[^/]+/event-destinations/[^/]+/?$",      "ses:UpdateConfigurationSetEventDestination"),
        rule("ses", "PUT",   "^/v2/email/configuration-sets/[^/]+/reputation-options/?$",            "ses:PutConfigurationSetReputationOptions"),
        rule("ses", "PUT",   "^/v2/email/configuration-sets/[^/]+/sending/?$",                       "ses:PutConfigurationSetSendingOptions"),
        rule("ses", "PUT",   "^/v2/email/configuration-sets/[^/]+/suppression-options/?$",           "ses:PutConfigurationSetSuppressionOptions"),
        rule("ses", "PUT",   "^/v2/email/configuration-sets/[^/]+/tracking-options/?$",              "ses:PutConfigurationSetTrackingOptions"),
        rule("ses", "PUT",   "^/v2/email/configuration-sets/[^/]+/vdm-options/?$",                   "ses:PutConfigurationSetVdmOptions"),
        rule("ses", "GET",   "^/v2/email/contact-lists/?$",                                          "ses:ListContactLists"),
        rule("ses", "POST",  "^/v2/email/contact-lists/?$",                                          "ses:CreateContactList"),
        rule("ses", "DELETE","^/v2/email/contact-lists/[^/]+/?$",                                    "ses:DeleteContactList"),
        rule("ses", "GET",   "^/v2/email/contact-lists/[^/]+/?$",                                    "ses:GetContactList"),
        rule("ses", "PUT",   "^/v2/email/contact-lists/[^/]+/?$",                                    "ses:UpdateContactList"),
        rule("ses", "POST",  "^/v2/email/contact-lists/[^/]+/contacts/?$",                           "ses:CreateContact"),
        rule("ses", "POST",  "^/v2/email/contact-lists/[^/]+/contacts/list/?$",                      "ses:ListContacts"),
        rule("ses", "DELETE","^/v2/email/contact-lists/[^/]+/contacts/[^/]+/?$",                     "ses:DeleteContact"),
        rule("ses", "GET",   "^/v2/email/contact-lists/[^/]+/contacts/[^/]+/?$",                     "ses:GetContact"),
        rule("ses", "PUT",   "^/v2/email/contact-lists/[^/]+/contacts/[^/]+/?$",                     "ses:UpdateContact"),
        rule("ses", "GET",   "^/v2/email/custom-verification-email-templates/?$",                    "ses:ListCustomVerificationEmailTemplates"),
        rule("ses", "POST",  "^/v2/email/custom-verification-email-templates/?$",                    "ses:CreateCustomVerificationEmailTemplate"),
        rule("ses", "DELETE","^/v2/email/custom-verification-email-templates/[^/]+/?$",              "ses:DeleteCustomVerificationEmailTemplate"),
        rule("ses", "GET",   "^/v2/email/custom-verification-email-templates/[^/]+/?$",              "ses:GetCustomVerificationEmailTemplate"),
        rule("ses", "PUT",   "^/v2/email/custom-verification-email-templates/[^/]+/?$",              "ses:UpdateCustomVerificationEmailTemplate"),
        rule("ses", "GET",   "^/v2/email/dedicated-ip-pools/?$",                                     "ses:ListDedicatedIpPools"),
        rule("ses", "POST",  "^/v2/email/dedicated-ip-pools/?$",                                     "ses:CreateDedicatedIpPool"),
        rule("ses", "DELETE","^/v2/email/dedicated-ip-pools/[^/]+/?$",                               "ses:DeleteDedicatedIpPool"),
        rule("ses", "GET",   "^/v2/email/dedicated-ip-pools/[^/]+/?$",                               "ses:GetDedicatedIpPool"),
        rule("ses", "PUT",   "^/v2/email/dedicated-ip-pools/[^/]+/scaling/?$",                       "ses:PutDedicatedIpPoolScalingAttributes"),
        rule("ses", "GET",   "^/v2/email/dedicated-ips/?$",                                          "ses:GetDedicatedIps"),
        rule("ses", "GET",   "^/v2/email/dedicated-ips/[^/]+/?$",                                    "ses:GetDedicatedIp"),
        rule("ses", "PUT",   "^/v2/email/dedicated-ips/[^/]+/pool/?$",                               "ses:PutDedicatedIpInPool"),
        rule("ses", "PUT",   "^/v2/email/dedicated-ips/[^/]+/warmup/?$",                             "ses:PutDedicatedIpWarmupAttributes"),
        rule("ses", "GET",   "^/v2/email/deliverability-dashboard/?$",                               "ses:GetDeliverabilityDashboardOptions"),
        rule("ses", "PUT",   "^/v2/email/deliverability-dashboard/?$",                               "ses:PutDeliverabilityDashboardOption"),
        rule("ses", "GET",   "^/v2/email/deliverability-dashboard/blacklist-report/?$",              "ses:GetBlacklistReports"),
        rule("ses", "GET",   "^/v2/email/deliverability-dashboard/campaigns/[^/]+/?$",               "ses:GetDomainDeliverabilityCampaign"),
        rule("ses", "GET",   "^/v2/email/deliverability-dashboard/domains/[^/]+/campaigns/?$",       "ses:ListDomainDeliverabilityCampaigns"),
        rule("ses", "GET",   "^/v2/email/deliverability-dashboard/statistics-report/[^/]+/?$",       "ses:GetDomainStatisticsReport"),
        rule("ses", "POST",  "^/v2/email/deliverability-dashboard/test/?$",                          "ses:CreateDeliverabilityTestReport"),
        rule("ses", "GET",   "^/v2/email/deliverability-dashboard/test-reports/?$",                  "ses:ListDeliverabilityTestReports"),
        rule("ses", "GET",   "^/v2/email/deliverability-dashboard/test-reports/[^/]+/?$",            "ses:GetDeliverabilityTestReport"),
        rule("ses", "POST",  "^/v2/email/email-address-insights/?$",                                 "ses:GetEmailAddressInsights"),
        rule("ses", "POST",  "^/v2/email/export-jobs/?$",                                            "ses:CreateExportJob"),
        rule("ses", "GET",   "^/v2/email/export-jobs/[^/]+/?$",                                      "ses:GetExportJob"),
        rule("ses", "PUT",   "^/v2/email/export-jobs/[^/]+/cancel/?$",                               "ses:CancelExportJob"),
        rule("ses", "GET",   "^/v2/email/identities/?$",                                             "ses:ListEmailIdentities"),
        rule("ses", "POST",  "^/v2/email/identities/?$",                                             "ses:CreateEmailIdentity"),
        rule("ses", "DELETE","^/v2/email/identities/[^/]+/?$",                                       "ses:DeleteEmailIdentity"),
        rule("ses", "GET",   "^/v2/email/identities/[^/]+/?$",                                       "ses:GetEmailIdentity"),
        rule("ses", "PUT",   "^/v2/email/identities/[^/]+/configuration-set/?$",                     "ses:PutEmailIdentityConfigurationSetAttributes"),
        rule("ses", "PUT",   "^/v2/email/identities/[^/]+/dkim/?$",                                  "ses:PutEmailIdentityDkimAttributes"),
        rule("ses", "PUT",   "^/v2/email/identities/[^/]+/dkim/signing/?$",                          "ses:PutEmailIdentityDkimSigningAttributes"),
        rule("ses", "PUT",   "^/v2/email/identities/[^/]+/feedback/?$",                              "ses:PutEmailIdentityFeedbackAttributes"),
        rule("ses", "PUT",   "^/v2/email/identities/[^/]+/mail-from/?$",                             "ses:PutEmailIdentityMailFromAttributes"),
        rule("ses", "GET",   "^/v2/email/identities/[^/]+/policies/?$",                              "ses:GetEmailIdentityPolicies"),
        rule("ses", "DELETE","^/v2/email/identities/[^/]+/policies/[^/]+/?$",                        "ses:DeleteEmailIdentityPolicy"),
        rule("ses", "POST",  "^/v2/email/identities/[^/]+/policies/[^/]+/?$",                        "ses:CreateEmailIdentityPolicy"),
        rule("ses", "PUT",   "^/v2/email/identities/[^/]+/policies/[^/]+/?$",                        "ses:UpdateEmailIdentityPolicy"),
        rule("ses", "POST",  "^/v2/email/identity/certificates/?$",                                  "ses:AssociateEmailIdentityCertificate"),
        rule("ses", "POST",  "^/v2/email/identity/certificates/delete/?$",                           "ses:DisassociateEmailIdentityCertificate"),
        rule("ses", "POST",  "^/v2/email/identity/certificates/list/?$",                             "ses:ListEmailIdentityCertificates"),
        rule("ses", "POST",  "^/v2/email/import-jobs/?$",                                            "ses:CreateImportJob"),
        rule("ses", "POST",  "^/v2/email/import-jobs/list/?$",                                       "ses:ListImportJobs"),
        rule("ses", "GET",   "^/v2/email/import-jobs/[^/]+/?$",                                      "ses:GetImportJob"),
        rule("ses", "GET",   "^/v2/email/insights/[^/]+/?$",                                         "ses:GetMessageInsights"),
        rule("ses", "POST",  "^/v2/email/list-configuration-sets/?$",                                "ses:ListConfigurationSets"),
        rule("ses", "POST",  "^/v2/email/list-export-jobs/?$",                                       "ses:ListExportJobs"),
        rule("ses", "POST",  "^/v2/email/list-identities/?$",                                        "ses:ListEmailIdentities"),
        rule("ses", "POST",  "^/v2/email/metrics/batch/?$",                                          "ses:BatchGetMetricData"),
        rule("ses", "GET",   "^/v2/email/multi-region-endpoints/?$",                                 "ses:ListMultiRegionEndpoints"),
        rule("ses", "POST",  "^/v2/email/multi-region-endpoints/?$",                                 "ses:CreateMultiRegionEndpoint"),
        rule("ses", "DELETE","^/v2/email/multi-region-endpoints/[^/]+/?$",                           "ses:DeleteMultiRegionEndpoint"),
        rule("ses", "GET",   "^/v2/email/multi-region-endpoints/[^/]+/?$",                           "ses:GetMultiRegionEndpoint"),
        rule("ses", "POST",  "^/v2/email/outbound-bulk-emails/?$",                                   "ses:SendBulkEmail"),
        rule("ses", "POST",  "^/v2/email/outbound-custom-verification-emails/?$",                    "ses:SendCustomVerificationEmail"),
        rule("ses", "POST",  "^/v2/email/outbound-emails/?$",                                        "ses:SendEmail"),
        rule("ses", "POST",  "^/v2/email/reputation/entities/?$",                                    "ses:ListReputationEntities"),
        rule("ses", "GET",   "^/v2/email/reputation/entities/[^/]+/[^/]+/?$",                        "ses:GetReputationEntity"),
        rule("ses", "PUT",   "^/v2/email/reputation/entities/[^/]+/[^/]+/customer-managed-status/?$","ses:UpdateReputationEntityCustomerManagedStatus"),
        rule("ses", "PUT",   "^/v2/email/reputation/entities/[^/]+/[^/]+/policy/?$",                 "ses:UpdateReputationEntityPolicy"),
        rule("ses", "POST",  "^/v2/email/resources/tenants/list/?$",                                 "ses:ListResourceTenants"),
        rule("ses", "GET",   "^/v2/email/suppression/addresses/?$",                                  "ses:ListSuppressedDestinations"),
        rule("ses", "PUT",   "^/v2/email/suppression/addresses/?$",                                  "ses:PutSuppressedDestination"),
        rule("ses", "DELETE","^/v2/email/suppression/addresses/[^/]+/?$",                            "ses:DeleteSuppressedDestination"),
        rule("ses", "GET",   "^/v2/email/suppression/addresses/[^/]+/?$",                            "ses:GetSuppressedDestination"),
        rule("ses", "DELETE","^/v2/email/tags/?$",                                                   "ses:UntagResource"),
        rule("ses", "GET",   "^/v2/email/tags/?$",                                                   "ses:ListTagsForResource"),
        rule("ses", "POST",  "^/v2/email/tags/?$",                                                   "ses:TagResource"),
        rule("ses", "GET",   "^/v2/email/templates/?$",                                              "ses:ListEmailTemplates"),
        rule("ses", "POST",  "^/v2/email/templates/?$",                                              "ses:CreateEmailTemplate"),
        rule("ses", "DELETE","^/v2/email/templates/[^/]+/?$",                                        "ses:DeleteEmailTemplate"),
        rule("ses", "GET",   "^/v2/email/templates/[^/]+/?$",                                        "ses:GetEmailTemplate"),
        rule("ses", "PUT",   "^/v2/email/templates/[^/]+/?$",                                        "ses:UpdateEmailTemplate"),
        rule("ses", "POST",  "^/v2/email/templates/[^/]+/render/?$",                                 "ses:TestRenderEmailTemplate"),
        rule("ses", "POST",  "^/v2/email/tenant/suppression/?$",                                     "ses:PutTenantSuppressionAttributes"),
        rule("ses", "POST",  "^/v2/email/tenants/?$",                                                "ses:CreateTenant"),
        rule("ses", "POST",  "^/v2/email/tenants/delete/?$",                                         "ses:DeleteTenant"),
        rule("ses", "POST",  "^/v2/email/tenants/get/?$",                                            "ses:GetTenant"),
        rule("ses", "POST",  "^/v2/email/tenants/list/?$",                                           "ses:ListTenants"),
        rule("ses", "POST",  "^/v2/email/tenants/resources/?$",                                      "ses:CreateTenantResourceAssociation"),
        rule("ses", "POST",  "^/v2/email/tenants/resources/delete/?$",                               "ses:DeleteTenantResourceAssociation"),
        rule("ses", "POST",  "^/v2/email/tenants/resources/list/?$",                                 "ses:ListTenantResources"),
        rule("ses", "POST",  "^/v2/email/update-configuration-sets/?$",                              "ses:UpdateConfigurationSet"),
        rule("ses", "POST",  "^/v2/email/vdm/recommendations/?$",                                    "ses:ListRecommendations"),

        // ── Kinesis ────────────────────────────────────────────────────────────
        rule("kinesis", "POST", ".*", "kinesis:*")
    );

    private static ActionRule rule(String service, String method, String path, String action) {
        return new ActionRule(service, method, Pattern.compile(path, Pattern.CASE_INSENSITIVE), action);
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
     * For REST-JSON services the action is derived from the path rule table.
     *
     * Returns {@code null} when the action is unknown (caller treats this as ALLOW).
     */
    public String resolve(String credentialScope, ContainerRequestContext ctx) {
        // REST requests with Action or Operation fields still use their method and path rules.
        if (isQueryRequest(ctx)) {
            String queryAction = queryAction(ctx);
            if (queryAction != null && !queryAction.isBlank()) {
                return credentialScope + ":" + queryAction;
            }
        }

        // JSON 1.1: X-Amz-Target → service:OperationName
        String target = ctx.getHeaderString("X-Amz-Target");
        if (target != null && target.contains(".")) {
            String operationName = target.substring(target.lastIndexOf('.') + 1);
            return credentialScope + ":" + operationName;
        }

        return resolveRoute(credentialScope, ctx);
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
     * <p>Returns {@code null} when no rule matches (caller treats this as ALLOW).
     */
    public String resolveRoute(String credentialScope, ContainerRequestContext ctx) {
        String method = ctx.getMethod().toUpperCase();
        String path = rawPath(ctx.getUriInfo());

        // S3 sub-resource override: the URL path alone doesn't distinguish
        // s3:GetObjectAcl / s3:PutObjectAcl from s3:GetObject / s3:PutObject
        // — only the {@code ?acl} query parameter does. Without this hook,
        // an actor with s3:GetObject permission could read ACLs that their
        // policy intends to forbid.
        if ("s3".equals(credentialScope)) {
            String s3SubAction = resolveS3SubResourceAction(method, ctx);
            if (s3SubAction != null) {
                return s3SubAction;
            }
        }

        for (ActionRule rule : RULES) {
            if (rule.service().equals(credentialScope)
                    && rule.method().equals(method)
                    && rule.pathPattern().matcher(path).find()) {
                return rule.action();
            }
        }

        LOG.debugv("No action mapping for {0} {1} {2} — defaulting to ALLOW", credentialScope, method, path);
        return null;
    }

    /**
     * The request path relative to the application root, as {@link UriInfo#getPath()} gives it, but
     * still percent-encoded.
     */
    private static String rawPath(UriInfo uriInfo) {
        String path = uriInfo.getRequestUri().getRawPath();
        String base = uriInfo.getBaseUri().getRawPath();
        if (base != null && base.length() > 1 && path.startsWith(base)) {
            path = path.substring(base.length() - 1);
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    /**
     * Returns the Query-protocol action from the form body while preserving the entity stream for
     * the controller. The controller dispatches only the form body, using the same Action-first,
     * Operation-second rule. A URL Action must not override the operation that will execute.
     */
    public String queryAction(ContainerRequestContext ctx) {
        return AwsQueryServiceResolver.action(
                RequestBodyReader.formField(ctx, "Action"),
                RequestBodyReader.formField(ctx, "Operation"));
    }

    /** The same form POST at the root that the Query controller dispatches. */
    public static boolean isQueryRequest(ContainerRequestContext ctx) {
        if (!"POST".equalsIgnoreCase(ctx.getMethod()) || ctx.getUriInfo() == null) {
            return false;
        }
        String path = ctx.getUriInfo().getPath();
        MediaType mediaType = ctx.getMediaType();
        return (path == null || path.isEmpty() || "/".equals(path))
                && mediaType != null
                && "application".equalsIgnoreCase(mediaType.getType())
                && "x-www-form-urlencoded".equalsIgnoreCase(mediaType.getSubtype());
    }

    /** One bucket sub-resource operation: the query parameter that selects it, and the IAM action. */
    private record SubResourceAction(String queryParameter, String action) {}

    private static SubResourceAction sub(String queryParameter, String action) {
        return new SubResourceAction(queryParameter, action);
    }

    private static final List<SubResourceAction> GET_BUCKET_SUBRESOURCES = List.of(
            sub("uploads",           "s3:ListBucketMultipartUploads"),
            sub("notification",      "s3:GetBucketNotification"),
            sub("versioning",        "s3:GetBucketVersioning"),
            sub("versions",          "s3:ListBucketVersions"),
            sub("location",          "s3:GetBucketLocation"),
            sub("tagging",           "s3:GetBucketTagging"),
            sub("object-lock",       "s3:GetBucketObjectLockConfiguration"),
            sub("website",           "s3:GetBucketWebsite"),
            sub("logging",           "s3:GetBucketLogging"),
            sub("policy",            "s3:GetBucketPolicy"),
            sub("cors",              "s3:GetBucketCORS"),
            sub("lifecycle",         "s3:GetLifecycleConfiguration"),
            sub("acl",               "s3:GetBucketAcl"),
            sub("encryption",        "s3:GetEncryptionConfiguration"),
            sub("publicAccessBlock", "s3:GetBucketPublicAccessBlock"),
            sub("ownershipControls", "s3:GetBucketOwnershipControls"),
            sub("requestPayment",    "s3:GetBucketRequestPayment"),
            sub("accelerate",        "s3:GetAccelerateConfiguration"),
            sub("replication",       "s3:GetReplicationConfiguration"),
            sub("metrics",           "s3:GetMetricsConfiguration"));

    private static final List<SubResourceAction> PUT_BUCKET_SUBRESOURCES = List.of(
            sub("notification",      "s3:PutBucketNotification"),
            sub("versioning",        "s3:PutBucketVersioning"),
            sub("tagging",           "s3:PutBucketTagging"),
            sub("object-lock",       "s3:PutBucketObjectLockConfiguration"),
            sub("website",           "s3:PutBucketWebsite"),
            sub("logging",           "s3:PutBucketLogging"),
            sub("policy",            "s3:PutBucketPolicy"),
            sub("cors",              "s3:PutBucketCORS"),
            sub("lifecycle",         "s3:PutLifecycleConfiguration"),
            sub("acl",               "s3:PutBucketAcl"),
            sub("encryption",        "s3:PutEncryptionConfiguration"),
            sub("publicAccessBlock", "s3:PutBucketPublicAccessBlock"),
            sub("ownershipControls", "s3:PutBucketOwnershipControls"),
            sub("requestPayment",    "s3:PutBucketRequestPayment"),
            sub("accelerate",        "s3:PutAccelerateConfiguration"),
            sub("replication",       "s3:PutReplicationConfiguration"),
            sub("metrics",           "s3:PutMetricsConfiguration"));

    // AWS gives only DeleteBucketPolicy and DeleteBucketWebsite their own action; removing any other
    // sub-resource is authorised by the same Put* action that sets it. ?accelerate is absent because
    // S3Controller rejects DELETE on it with 405, so no mapping should claim the request.
    private static final List<SubResourceAction> DELETE_BUCKET_SUBRESOURCES = List.of(
            sub("tagging",           "s3:DeleteBucketTagging"),
            sub("website",           "s3:DeleteBucketWebsite"),
            sub("policy",            "s3:DeleteBucketPolicy"),
            sub("cors",              "s3:PutBucketCORS"),
            sub("lifecycle",         "s3:PutLifecycleConfiguration"),
            sub("encryption",        "s3:PutEncryptionConfiguration"),
            sub("publicAccessBlock", "s3:PutBucketPublicAccessBlock"),
            sub("ownershipControls", "s3:PutBucketOwnershipControls"),
            sub("replication",       "s3:PutReplicationConfiguration"),
            sub("metrics",           "s3:PutMetricsConfiguration"));

    private static String resolveS3SubResourceAction(String method, ContainerRequestContext ctx) {
        MultivaluedMap<String, String> params = ctx.getUriInfo().getQueryParameters();
        // /{bucket}?acl -> bucket-level; /{bucket}/{key}?acl -> object-level.
        // A trailing slash is a valid key character, so /bucket/folder/?acl is an object request -
        // we cannot use endsWith("/") to infer bucket-level.
        String path = ctx.getUriInfo().getPath();
        String stripped = path.startsWith("/") ? path.substring(1) : path;
        int firstSlash = stripped.indexOf('/');
        boolean isBucketLevel = firstSlash < 0 || firstSlash == stripped.length() - 1;
        if (!isBucketLevel) {
            return objectSubResourceAction(method, params);
        }
        List<SubResourceAction> chain = switch (method) {
            case "GET" -> GET_BUCKET_SUBRESOURCES;
            case "PUT" -> PUT_BUCKET_SUBRESOURCES;
            case "DELETE" -> DELETE_BUCKET_SUBRESOURCES;
            default -> List.of();
        };
        for (SubResourceAction entry : chain) {
            if (params.containsKey(entry.queryParameter())) {
                return entry.action();
            }
        }
        return null;
    }

    private static String objectSubResourceAction(String method, MultivaluedMap<String, String> params) {
        if (params.containsKey("acl")) {
            return switch (method) {
                case "GET" -> "s3:GetObjectAcl";
                case "PUT" -> "s3:PutObjectAcl";
                default -> null;
            };
        }
        if (params.containsKey("tagging")) {
            return switch (method) {
                case "GET" -> "s3:GetObjectTagging";
                case "PUT" -> "s3:PutObjectTagging";
                case "DELETE" -> "s3:DeleteObjectTagging";
                default -> null;
            };
        }
        return null;
    }

}
