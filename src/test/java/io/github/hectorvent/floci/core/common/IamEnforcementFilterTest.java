package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.iam.IamActionRegistry;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.cloudtrail.CloudTrailService;
import io.github.hectorvent.floci.services.iam.ResourceArnBuilder;
import io.github.hectorvent.floci.services.iam.ScpProvider;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.container.ContainerRequestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link IamEnforcementFilter#accessDeniedResponse}, focused on
 * the protocol-aware response shape. AWS SDKs hard-fail on wrong-shape error
 * payloads — an XML parser blows up on a leading {@code "{"} and a JSON parser
 * blows up on a leading {@code "<"} — so each protocol has to get the right
 * envelope.
 */
class IamEnforcementFilterTest {

    private EmulatorConfig config;
    private EmulatorConfig.ServicesConfig services;
    private EmulatorConfig.IamServiceConfig iamConfig;
    private AccountResolver accountResolver;
    private IamService iamService;
    private IamPolicyEvaluator evaluator;
    private IamActionRegistry actionRegistry;
    private ResourceArnBuilder arnBuilder;
    private RequestContext requestContext;
    private IamConditionContextResolver conditionContextResolver;
    private ResolvedServiceCatalog catalog;

    @BeforeEach
    void setUp() {
        config = mock(EmulatorConfig.class);
        services = mock(EmulatorConfig.ServicesConfig.class);
        iamConfig = mock(EmulatorConfig.IamServiceConfig.class);
        accountResolver = mock(AccountResolver.class);
        iamService = mock(IamService.class);
        evaluator = mock(IamPolicyEvaluator.class);
        actionRegistry = mock(IamActionRegistry.class);
        arnBuilder = mock(ResourceArnBuilder.class);
        requestContext = new RequestContext();
        conditionContextResolver = mock(IamConditionContextResolver.class);
        catalog = mock(ResolvedServiceCatalog.class);

        when(config.services()).thenReturn(services);
        when(services.iam()).thenReturn(iamConfig);
        when(iamConfig.enforcementEnabled()).thenReturn(true);
        when(config.defaultRegion()).thenReturn("us-east-1");
        when(arnBuilder.buildResources(any(), any(), any(), any())).thenReturn(List.of("*"));
        when(arnBuilder.build(any(), any(), any(), any())).thenReturn("*");
        // Default: scopes are already canonical. Alias handling is asserted explicitly below.
        when(catalog.canonicalCredentialScope(anyString())).thenAnswer(inv -> inv.getArgument(0));
    }

    private IamEnforcementFilter newFilter() {
        @SuppressWarnings("unchecked")
        jakarta.enterprise.inject.Instance<io.github.hectorvent.floci.services.iam.ScpProvider> scpProvider =
                mock(jakarta.enterprise.inject.Instance.class);
        when(scpProvider.isResolvable()).thenReturn(false);
        return new IamEnforcementFilter(
                config, accountResolver, iamService, evaluator, actionRegistry, arnBuilder,
                requestContext, conditionContextResolver,
                mock(CloudTrailService.class),
                mock(io.quarkus.vertx.http.runtime.CurrentVertxRequest.class),
                catalog, scpProvider);
    }

    @Test
    void filterBuildsResourceArnWithRequestContextAccount() {
        ContainerRequestContext containerRequest = mock(ContainerRequestContext.class);

        String auth = "AWS4-HMAC-SHA256 Credential=ASIASESSION/20260629/us-east-1/lambda/aws4_request, "
                + "SignedHeaders=host, Signature=abc";
        requestContext.setAccountId("222233334444");
        requestContext.setRegion("us-east-1");

        when(accountResolver.extractAccessKeyId(auth)).thenReturn("ASIASESSION");
        when(accountResolver.resolve(auth)).thenReturn("000000000000");
        when(containerRequest.getHeaderString("Authorization")).thenReturn(auth);
        when(actionRegistry.resolve("lambda", containerRequest)).thenReturn("lambda:InvokeFunction");
        when(iamService.resolveCallerContext("ASIASESSION"))
                .thenReturn(CallerContext.of(List.of("""
                        {"Version":"2012-10-17","Statement":[
                          {"Effect":"Allow","Action":"lambda:InvokeFunction",
                           "Resource":"arn:aws:lambda:us-east-1:222233334444:function:fn"}
                        ]}""")));
        when(arnBuilder.buildResources("lambda", containerRequest, "us-east-1", "222233334444"))
                .thenReturn(List.of("arn:aws:lambda:us-east-1:222233334444:function:fn"));
        when(evaluator.evaluate(
                any(),
                isNull(),
                eq("lambda:InvokeFunction"),
                eq("arn:aws:lambda:us-east-1:222233334444:function:fn"),
                isNull()))
                .thenReturn(IamPolicyEvaluator.Decision.ALLOW);
        when(conditionContextResolver.resolve("lambda", "lambda:InvokeFunction", containerRequest))
                .thenReturn(null);

        IamEnforcementFilter filter = newFilter();

        filter.filter(containerRequest);

        verify(arnBuilder).buildResources("lambda", containerRequest, "us-east-1", "222233334444");
    }

    @Test
    void filterBuildsResourceArnForDynamoDbTable() {
        ContainerRequestContext containerRequest = mock(ContainerRequestContext.class);
        String auth = "AWS4-HMAC-SHA256 Credential=ASIASESSION/20260902/us-east-1/dynamodb/aws4_request, "
                + "SignedHeaders=host, Signature=abc";
        requestContext.setAccountId("000000000000");
        requestContext.setRegion("us-east-1");

        when(accountResolver.extractAccessKeyId(auth)).thenReturn("ASIASESSION");
        when(accountResolver.resolve(auth)).thenReturn("000000000000");
        when(containerRequest.getHeaderString("Authorization")).thenReturn(auth);
        when(actionRegistry.resolve("dynamodb", containerRequest)).thenReturn("dynamodb:GetItem");
        when(iamService.resolveCallerContext("ASIASESSION"))
                .thenReturn(CallerContext.of(List.of("""
                        {"Version":"2012-10-17","Statement":[
                          {"Effect":"Allow","Action":"dynamodb:GetItem",
                           "Resource":"arn:aws:dynamodb:us-east-1:000000000000:table/FgacTable"}
                        ]}""")));
        when(arnBuilder.buildResources("dynamodb", containerRequest, "us-east-1", "000000000000"))
                .thenReturn(List.of("arn:aws:dynamodb:us-east-1:000000000000:table/FgacTable"));
        when(evaluator.evaluate(
                any(),
                isNull(),
                eq("dynamodb:GetItem"),
                eq("arn:aws:dynamodb:us-east-1:000000000000:table/FgacTable"),
                isNull()))
                .thenReturn(IamPolicyEvaluator.Decision.ALLOW);
        when(conditionContextResolver.resolve("dynamodb", "dynamodb:GetItem", containerRequest))
                .thenReturn(null);

        IamEnforcementFilter filter = newFilter();

        filter.filter(containerRequest);

        verify(arnBuilder).buildResources("dynamodb", containerRequest, "us-east-1", "000000000000");
        verify(containerRequest, never()).abortWith(any());
    }

    @Test
    void filterEvaluatesAllResourcesForMultiTableDynamoDbRequestAndAllowsWhenAllAllowed() {
        ContainerRequestContext containerRequest = mock(ContainerRequestContext.class);
        String auth = "AWS4-HMAC-SHA256 Credential=ASIASESSION/20260902/us-east-1/dynamodb/aws4_request, "
                + "SignedHeaders=host, Signature=abc";
        requestContext.setAccountId("000000000000");
        requestContext.setRegion("us-east-1");

        when(accountResolver.extractAccessKeyId(auth)).thenReturn("ASIASESSION");
        when(accountResolver.resolve(auth)).thenReturn("000000000000");
        when(containerRequest.getHeaderString("Authorization")).thenReturn(auth);
        when(actionRegistry.resolve("dynamodb", containerRequest)).thenReturn("dynamodb:BatchGetItem");
        when(iamService.resolveCallerContext("ASIASESSION"))
                .thenReturn(CallerContext.of(List.of("{}")));
        when(arnBuilder.buildResources("dynamodb", containerRequest, "us-east-1", "000000000000"))
                .thenReturn(List.of(
                        "arn:aws:dynamodb:us-east-1:000000000000:table/TableA",
                        "arn:aws:dynamodb:us-east-1:000000000000:table/TableB"
                ));
        when(evaluator.evaluate(any(), isNull(), eq("dynamodb:BatchGetItem"), eq("arn:aws:dynamodb:us-east-1:000000000000:table/TableA"), isNull()))
                .thenReturn(IamPolicyEvaluator.Decision.ALLOW);
        when(evaluator.evaluate(any(), isNull(), eq("dynamodb:BatchGetItem"), eq("arn:aws:dynamodb:us-east-1:000000000000:table/TableB"), isNull()))
                .thenReturn(IamPolicyEvaluator.Decision.ALLOW);
        when(conditionContextResolver.resolve("dynamodb", "dynamodb:BatchGetItem", containerRequest))
                .thenReturn(null);

        IamEnforcementFilter filter = newFilter();
        filter.filter(containerRequest);

        verify(evaluator).evaluate(any(), isNull(), eq("dynamodb:BatchGetItem"), eq("arn:aws:dynamodb:us-east-1:000000000000:table/TableA"), isNull());
        verify(evaluator).evaluate(any(), isNull(), eq("dynamodb:BatchGetItem"), eq("arn:aws:dynamodb:us-east-1:000000000000:table/TableB"), isNull());
        verify(containerRequest, never()).abortWith(any());
    }

    @Test
    void filterAbortsWhenAnyResourceInMultiTableRequestIsDenied() {
        ContainerRequestContext containerRequest = mock(ContainerRequestContext.class);
        String auth = "AWS4-HMAC-SHA256 Credential=ASIASESSION/20260902/us-east-1/dynamodb/aws4_request, "
                + "SignedHeaders=host, Signature=abc";
        requestContext.setAccountId("000000000000");
        requestContext.setRegion("us-east-1");

        when(accountResolver.extractAccessKeyId(auth)).thenReturn("ASIASESSION");
        when(accountResolver.resolve(auth)).thenReturn("000000000000");
        when(containerRequest.getHeaderString("Authorization")).thenReturn(auth);
        when(actionRegistry.resolve("dynamodb", containerRequest)).thenReturn("dynamodb:BatchGetItem");
        when(iamService.resolveCallerContext("ASIASESSION"))
                .thenReturn(CallerContext.of(List.of("{}")));
        when(arnBuilder.buildResources("dynamodb", containerRequest, "us-east-1", "000000000000"))
                .thenReturn(List.of(
                        "arn:aws:dynamodb:us-east-1:000000000000:table/TableA",
                        "arn:aws:dynamodb:us-east-1:000000000000:table/TableB"
                ));
        when(evaluator.evaluate(any(), isNull(), eq("dynamodb:BatchGetItem"), eq("arn:aws:dynamodb:us-east-1:000000000000:table/TableA"), isNull()))
                .thenReturn(IamPolicyEvaluator.Decision.ALLOW);
        when(evaluator.evaluate(any(), isNull(), eq("dynamodb:BatchGetItem"), eq("arn:aws:dynamodb:us-east-1:000000000000:table/TableB"), isNull()))
                .thenReturn(IamPolicyEvaluator.Decision.DENY);
        when(conditionContextResolver.resolve("dynamodb", "dynamodb:BatchGetItem", containerRequest))
                .thenReturn(null);

        IamEnforcementFilter filter = newFilter();
        filter.filter(containerRequest);

        verify(containerRequest).abortWith(any());
    }

    @Test
    void getCallerIdentityBypassesPolicyEnforcement() {
        ContainerRequestContext containerRequest = mock(ContainerRequestContext.class);
        String auth = "AWS4-HMAC-SHA256 Credential=ASIASESSION/20260720/us-east-1/sts/aws4_request, "
                + "SignedHeaders=host, Signature=abc";

        when(accountResolver.extractAccessKeyId(auth)).thenReturn("ASIASESSION");
        when(containerRequest.getHeaderString("Authorization")).thenReturn(auth);
        when(actionRegistry.resolve("sts", containerRequest)).thenReturn("sts:GetCallerIdentity");

        IamEnforcementFilter filter = newFilter();

        filter.filter(containerRequest);

        verify(iamService, never()).resolveCallerContext(any());
        verify(evaluator, never()).evaluate(any(), any(), any(), any(), any());
        verify(containerRequest, never()).abortWith(any());
    }

    @Test
    void filterPassesS3ListBucketConditionContext() {
        ContainerRequestContext containerRequest = mock(ContainerRequestContext.class);
        Map<String, List<String>> conditions = Map.of("s3:prefix", List.of("my_namespace/table/"));

        String auth = "AWS4-HMAC-SHA256 Credential=ASIASESSION/20260706/us-east-1/s3/aws4_request, "
                + "SignedHeaders=host, Signature=abc";
        requestContext.setAccountId("222233334444");
        requestContext.setRegion("us-east-1");

        when(accountResolver.extractAccessKeyId(auth)).thenReturn("ASIASESSION");
        when(containerRequest.getHeaderString("Authorization")).thenReturn(auth);
        when(actionRegistry.resolve("s3", containerRequest)).thenReturn("s3:ListBucket");
        when(iamService.resolveCallerContext("ASIASESSION"))
                .thenReturn(CallerContext.of(List.of("""
                        {"Version":"2012-10-17","Statement":[
                          {"Effect":"Allow","Action":"s3:ListBucket","Resource":"*"}
                        ]}""")));
        when(arnBuilder.buildResources("s3", containerRequest, "us-east-1", "222233334444"))
                .thenReturn(List.of("arn:aws:s3:::bucket"));
        when(conditionContextResolver.resolve("s3", "s3:ListBucket", containerRequest))
                .thenReturn(conditions);
        when(evaluator.evaluate(any(), isNull(), eq("s3:ListBucket"), eq("arn:aws:s3:::bucket"), eq(conditions)))
                .thenReturn(IamPolicyEvaluator.Decision.ALLOW);

        IamEnforcementFilter filter = newFilter();

        filter.filter(containerRequest);

        verify(evaluator).evaluate(any(), isNull(), eq("s3:ListBucket"),
                eq("arn:aws:s3:::bucket"), eq(conditions));
    }

    @Test
    void aliasScopeIsEnforcedUnderItsCanonicalName() {
        // S3 Express clients sign with the s3express scope. Everything keyed by scope — action
        // rules, ARN building, condition keys — knows only "s3", so without normalisation the
        // action resolves to null and the filter allows the request through unchecked.
        ContainerRequestContext containerRequest = mock(ContainerRequestContext.class);

        String auth = "AWS4-HMAC-SHA256 Credential=ASIASESSION/20260726/us-east-1/s3express/aws4_request, "
                + "SignedHeaders=host, Signature=abc";
        requestContext.setAccountId("222233334444");
        requestContext.setRegion("us-east-1");

        when(catalog.canonicalCredentialScope("s3express")).thenReturn("s3");
        when(accountResolver.extractAccessKeyId(auth)).thenReturn("ASIASESSION");
        when(containerRequest.getHeaderString("Authorization")).thenReturn(auth);
        when(actionRegistry.resolve("s3", containerRequest)).thenReturn("s3:GetObject");
        when(iamService.resolveCallerContext("ASIASESSION"))
                .thenReturn(CallerContext.of(List.of("""
                        {"Version":"2012-10-17","Statement":[
                          {"Effect":"Allow","Action":"s3:PutObject","Resource":"*"}
                        ]}""")));
        when(arnBuilder.buildResources(eq("s3"), eq(containerRequest), anyString(), anyString()))
                .thenReturn(List.of("arn:aws:s3:::bucket/key"));
        when(evaluator.evaluate(any(), any(), any(), any(), any()))
                .thenReturn(IamPolicyEvaluator.Decision.DENY);

        newFilter().filter(containerRequest);

        // Everything keyed by scope must see the canonical name, not the alias.
        verify(actionRegistry).resolve("s3", containerRequest);
        verify(arnBuilder).buildResources(eq("s3"), eq(containerRequest), anyString(), anyString());
        verify(conditionContextResolver).resolve("s3", "s3:GetObject", containerRequest);
        // The policy above grants only s3:PutObject, so a GetObject signed as s3express is denied.
        verify(containerRequest).abortWith(any(Response.class));
    }

    // FullAWSAccess baseline auto-attached to every OU/account when SCP enforcement is on.
    private static final String FULL_AWS_ACCESS =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"*\",\"Resource\":\"*\"}]}";
    private static final String DENY_LEAVE_ORG =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Deny\","
            + "\"Action\":\"organizations:LeaveOrganization\",\"Resource\":\"*\"}]}";

    /**
     * Filter whose SCP provider is resolvable (returns {@code scp}) and whose policy evaluator is
     * real, so the SCP deny-ceiling is exercised in-process rather than mocked away.
     */
    private IamEnforcementFilter newFilterWithScp(ScpProvider scp) {
        @SuppressWarnings("unchecked")
        Instance<ScpProvider> scpProvider = mock(Instance.class);
        when(scpProvider.isResolvable()).thenReturn(true);
        when(scpProvider.get()).thenReturn(scp);
        return new IamEnforcementFilter(
                config, accountResolver, iamService, new IamPolicyEvaluator(new ObjectMapper()),
                actionRegistry, arnBuilder, requestContext, conditionContextResolver,
                mock(CloudTrailService.class),
                mock(io.quarkus.vertx.http.runtime.CurrentVertxRequest.class),
                catalog, scpProvider);
    }

    @Test
    void scpDeniesLeaveOrganizationForBareAccountRootPrincipal() {
        // floci's account-root principal is a bare 12-digit account-id key: resolveCallerContext
        // returns null for it, but in AWS the account root is still bounded by SCPs. A workload OU
        // carrying a Deny on organizations:LeaveOrganization must therefore block the member.
        ContainerRequestContext containerRequest = mock(ContainerRequestContext.class);
        String account = "111122223333";
        String auth = "AWS4-HMAC-SHA256 Credential=" + account
                + "/20260629/us-east-1/organizations/aws4_request, SignedHeaders=host, Signature=abc";
        requestContext.setAccountId(account);
        requestContext.setRegion("us-east-1");

        when(accountResolver.extractAccessKeyId(auth)).thenReturn(account);
        when(accountResolver.resolve(auth)).thenReturn(account);
        when(containerRequest.getHeaderString("Authorization")).thenReturn(auth);
        when(containerRequest.getMediaType())
                .thenReturn(MediaType.valueOf("application/x-amz-json-1.1"));
        when(actionRegistry.resolve("organizations", containerRequest))
                .thenReturn("organizations:LeaveOrganization");
        when(iamService.resolveCallerContext(account)).thenReturn(null); // account root: not an IAM identity
        when(arnBuilder.build(eq("organizations"), eq(containerRequest), eq("us-east-1"), eq(account)))
                .thenReturn("*");
        when(conditionContextResolver.resolve(eq("organizations"), anyString(), eq(containerRequest)))
                .thenReturn(null);

        ScpProvider scp = mock(ScpProvider.class);
        // root(FullAWSAccess) → OU(FullAWSAccess + DenyLeaveOrg) → account(FullAWSAccess)
        when(scp.effectiveScpLevels(account)).thenReturn(List.of(
                List.of(FULL_AWS_ACCESS),
                List.of(FULL_AWS_ACCESS, DENY_LEAVE_ORG),
                List.of(FULL_AWS_ACCESS)));

        ArgumentCaptor<Response> captor = ArgumentCaptor.forClass(Response.class);

        newFilterWithScp(scp).filter(containerRequest);

        verify(containerRequest).abortWith(captor.capture());
        assertEquals(403, captor.getValue().getStatus());
    }

    @Test
    void scpAllowsNonDeniedActionForBareAccountRootPrincipal() {
        // Same account root + SCP chain, but an action the SCP does not deny must pass: the
        // FullAWSAccess baseline allows it at every level, so we must not over-block.
        ContainerRequestContext containerRequest = mock(ContainerRequestContext.class);
        String account = "111122223333";
        String auth = "AWS4-HMAC-SHA256 Credential=" + account
                + "/20260629/us-east-1/organizations/aws4_request, SignedHeaders=host, Signature=abc";
        requestContext.setAccountId(account);
        requestContext.setRegion("us-east-1");

        when(accountResolver.extractAccessKeyId(auth)).thenReturn(account);
        when(accountResolver.resolve(auth)).thenReturn(account);
        when(containerRequest.getHeaderString("Authorization")).thenReturn(auth);
        when(actionRegistry.resolve("organizations", containerRequest))
                .thenReturn("organizations:DescribeOrganization");
        when(iamService.resolveCallerContext(account)).thenReturn(null);
        when(arnBuilder.buildResources(eq("organizations"), eq(containerRequest), eq("us-east-1"), eq(account)))
                .thenReturn(List.of("*"));
        when(conditionContextResolver.resolve(eq("organizations"), anyString(), eq(containerRequest)))
                .thenReturn(null);

        ScpProvider scp = mock(ScpProvider.class);
        when(scp.effectiveScpLevels(account)).thenReturn(List.of(
                List.of(FULL_AWS_ACCESS),
                List.of(FULL_AWS_ACCESS, DENY_LEAVE_ORG),
                List.of(FULL_AWS_ACCESS)));

        newFilterWithScp(scp).filter(containerRequest);

        verify(containerRequest, never()).abortWith(any());
        // Prove the request went THROUGH evaluation rather than taking the unknown-key bypass:
        // arnBuilder.buildResources sits after the caller-resolution branch, so it only fires for a request
        // that was actually evaluated. Without this, the never()-abort assertion would also pass on
        // a bypass, making it no stronger than the pre-fix behavior.
        verify(arnBuilder).buildResources(eq("organizations"), eq(containerRequest), eq("us-east-1"), eq(account));
    }

    // The realistic baseline guardrail from .temp/org-functional-test.sh: DenyLeaveOrg plus a
    // DenyRootUser that fires when aws:PrincipalArn matches the account root. floci now populates
    // aws:PrincipalArn as arn:aws:iam::<account>:root for the synthesized account-root principal
    // (the same principal SCPs already enforce against), so this guardrail's DenyRootUser
    // statement fires for every action the account-root principal takes — not just the one
    // DenyLeaveOrg explicitly targets. This test locks in that faithful behavior: a maintainer
    // review (pgermosen, PR #2637) flagged the earlier inert-DenyRootUser behavior as an
    // inconsistency, since SCPs already treat this principal as root but the condition context
    // didn't reflect it.
    private static final String WORKLOAD_GUARDRAILS =
            "{\"Version\":\"2012-10-17\",\"Statement\":["
            + "{\"Sid\":\"DenyLeaveOrg\",\"Effect\":\"Deny\","
            + "\"Action\":[\"organizations:LeaveOrganization\"],\"Resource\":\"*\"},"
            + "{\"Sid\":\"DenyRootUser\",\"Effect\":\"Deny\",\"Action\":\"*\",\"Resource\":\"*\","
            + "\"Condition\":{\"StringLike\":{\"aws:PrincipalArn\":\"arn:aws:iam::*:root\"}}}]}";

    @Test
    void workloadGuardrailDenyRootUserFiresForAccountRootPrincipal() {
        // A non-DenyLeaveOrg action is now ALSO denied under the two-statement baseline guardrail:
        // DenyRootUser's blanket Action:"*" fires because aws:PrincipalArn now matches the
        // synthesized account-root ARN.
        ContainerRequestContext otherAction = mock(ContainerRequestContext.class);
        String account = "111122223333";
        String auth = "AWS4-HMAC-SHA256 Credential=" + account
                + "/20260629/us-east-1/organizations/aws4_request, SignedHeaders=host, Signature=abc";
        requestContext.setAccountId(account);
        requestContext.setRegion("us-east-1");

        when(accountResolver.extractAccessKeyId(auth)).thenReturn(account);
        when(accountResolver.resolve(auth)).thenReturn(account);
        when(otherAction.getHeaderString("Authorization")).thenReturn(auth);
        when(otherAction.getMediaType()).thenReturn(MediaType.valueOf("application/x-amz-json-1.1"));
        when(actionRegistry.resolve("organizations", otherAction))
                .thenReturn("organizations:DescribeOrganization");
        when(iamService.resolveCallerContext(account)).thenReturn(null);
        when(arnBuilder.build(eq("organizations"), eq(otherAction), eq("us-east-1"), eq(account)))
                .thenReturn("*");
        when(conditionContextResolver.resolve(eq("organizations"), anyString(), eq(otherAction)))
                .thenReturn(null);

        ScpProvider scp = mock(ScpProvider.class);
        when(scp.effectiveScpLevels(account)).thenReturn(List.of(
                List.of(FULL_AWS_ACCESS),
                List.of(FULL_AWS_ACCESS, WORKLOAD_GUARDRAILS)));

        ArgumentCaptor<Response> otherCaptor = ArgumentCaptor.forClass(Response.class);
        newFilterWithScp(scp).filter(otherAction);
        verify(otherAction).abortWith(otherCaptor.capture());
        assertEquals(403, otherCaptor.getValue().getStatus());

        // ...and the action DenyLeaveOrg explicitly targets is denied too (doubly so now).
        ContainerRequestContext denied = mock(ContainerRequestContext.class);
        when(denied.getHeaderString("Authorization")).thenReturn(auth);
        when(denied.getMediaType()).thenReturn(MediaType.valueOf("application/x-amz-json-1.1"));
        when(actionRegistry.resolve("organizations", denied))
                .thenReturn("organizations:LeaveOrganization");
        when(arnBuilder.build(eq("organizations"), eq(denied), eq("us-east-1"), eq(account)))
                .thenReturn("*");
        when(conditionContextResolver.resolve(eq("organizations"), anyString(), eq(denied)))
                .thenReturn(null);

        ArgumentCaptor<Response> captor = ArgumentCaptor.forClass(Response.class);
        newFilterWithScp(scp).filter(denied);
        verify(denied).abortWith(captor.capture());
        assertEquals(403, captor.getValue().getStatus());
    }

    @Test
    void accountRootPrincipalPopulatesRootPrincipalArnInConditionContext() {
        // Direct assertion that aws:PrincipalArn is set to the AWS root-ARN shape for the
        // synthesized account-root principal: an SCP level that allows everything except an
        // action explicitly conditioned on the exact root ARN must deny only that action.
        String account = "444455556666";
        String rootArnDeny = "{\"Version\":\"2012-10-17\",\"Statement\":["
                + "{\"Effect\":\"Deny\",\"Action\":\"s3:ListBucket\",\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"aws:PrincipalArn\":\"arn:aws:iam::"
                + account + ":root\"}}}]}";
        String auth = "AWS4-HMAC-SHA256 Credential=" + account
                + "/20260629/us-east-1/s3/aws4_request, SignedHeaders=host, Signature=abc";
        requestContext.setAccountId(account);
        requestContext.setRegion("us-east-1");

        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        when(ctx.getHeaderString("Authorization")).thenReturn(auth);
        when(ctx.getMediaType()).thenReturn(MediaType.valueOf("application/x-amz-json-1.1"));
        when(accountResolver.extractAccessKeyId(auth)).thenReturn(account);
        when(accountResolver.resolve(auth)).thenReturn(account);
        when(actionRegistry.resolve("s3", ctx)).thenReturn("s3:ListBucket");
        when(iamService.resolveCallerContext(account)).thenReturn(null);
        when(arnBuilder.build(eq("s3"), eq(ctx), eq("us-east-1"), eq(account))).thenReturn("*");
        when(conditionContextResolver.resolve(eq("s3"), anyString(), eq(ctx))).thenReturn(null);

        ScpProvider scp = mock(ScpProvider.class);
        when(scp.effectiveScpLevels(account)).thenReturn(List.of(
                List.of(FULL_AWS_ACCESS), List.of(FULL_AWS_ACCESS, rootArnDeny)));

        ArgumentCaptor<Response> captor = ArgumentCaptor.forClass(Response.class);
        newFilterWithScp(scp).filter(ctx);
        verify(ctx).abortWith(captor.capture());
        assertEquals(403, captor.getValue().getStatus());
    }

    // aws:PrincipalArn is populated only for principals whose ARN is known — IAM users and
    // assumed-role sessions (IamService.resolveCallerArn). A condition-scoped SCP keyed on the
    // principal ARN must therefore fire for a real IAM identity. It stays inert for the bare
    // account-root key, whose resolveCallerArn is empty (see the workload-guardrails test above).
    private static final String DENY_IAM_USER_PRINCIPAL =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Deny\",\"Action\":\"*\","
            + "\"Resource\":\"*\",\"Condition\":{\"StringLike\":"
            + "{\"aws:PrincipalArn\":\"arn:aws:iam::*:user/*\"}}}]}";

    @Test
    void scpConditionOnPrincipalArnDeniesRealIamIdentity() {
        ContainerRequestContext containerRequest = mock(ContainerRequestContext.class);
        String account = "111122223333";
        String akid = "AKIAALICEEXAMPLE";
        String auth = "AWS4-HMAC-SHA256 Credential=" + akid
                + "/20260629/us-east-1/organizations/aws4_request, SignedHeaders=host, Signature=abc";
        requestContext.setAccountId(account);
        requestContext.setRegion("us-east-1");

        when(accountResolver.extractAccessKeyId(auth)).thenReturn(akid);
        when(accountResolver.resolve(auth)).thenReturn(account);
        when(containerRequest.getHeaderString("Authorization")).thenReturn(auth);
        when(containerRequest.getMediaType()).thenReturn(MediaType.valueOf("application/x-amz-json-1.1"));
        when(actionRegistry.resolve("organizations", containerRequest))
                .thenReturn("organizations:DescribeOrganization");
        // A real IAM user: full-access identity policy plus a known principal ARN.
        when(iamService.resolveCallerContext(akid))
                .thenReturn(CallerContext.of(List.of(FULL_AWS_ACCESS)));
        when(iamService.resolveCallerArn(akid))
                .thenReturn(Optional.of("arn:aws:iam::" + account + ":user/alice"));
        when(arnBuilder.build(eq("organizations"), eq(containerRequest), eq("us-east-1"), eq(account)))
                .thenReturn("*");
        when(conditionContextResolver.resolve(eq("organizations"), anyString(), eq(containerRequest)))
                .thenReturn(null);

        ScpProvider scp = mock(ScpProvider.class);
        when(scp.effectiveScpLevels(account)).thenReturn(List.of(
                List.of(FULL_AWS_ACCESS),
                List.of(FULL_AWS_ACCESS, DENY_IAM_USER_PRINCIPAL)));

        ArgumentCaptor<Response> captor = ArgumentCaptor.forClass(Response.class);
        newFilterWithScp(scp).filter(containerRequest);

        // aws:PrincipalArn is now populated for the IAM user, so the principal-scoped Deny matches.
        verify(containerRequest).abortWith(captor.capture());
        assertEquals(403, captor.getValue().getStatus());
    }

    // Populating aws:PrincipalArn is bidirectional: it lets a principal-scoped Deny fire (above) AND
    // lets a principal-scoped Allow match. An identity policy that grants access only when the caller
    // is an IAM user must therefore ALLOW a real IAM user. Before aws:PrincipalArn was populated the
    // key was absent, the StringLike failed, the sole Allow never matched, and the request was denied
    // by default — so stubbing resolveCallerArn empty makes this test RED, proving it is load-bearing.
    private static final String ALLOW_IF_IAM_USER_PRINCIPAL =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"*\","
            + "\"Resource\":\"*\",\"Condition\":{\"StringLike\":"
            + "{\"aws:PrincipalArn\":\"arn:aws:iam::*:user/*\"}}}]}";

    @Test
    void identityPolicyAllowGatedOnPrincipalArnMatchesRealIamIdentity() {
        ContainerRequestContext containerRequest = mock(ContainerRequestContext.class);
        String account = "111122223333";
        String akid = "AKIABOBEXAMPLE";
        String auth = "AWS4-HMAC-SHA256 Credential=" + akid
                + "/20260629/us-east-1/organizations/aws4_request, SignedHeaders=host, Signature=abc";
        requestContext.setAccountId(account);
        requestContext.setRegion("us-east-1");

        when(accountResolver.extractAccessKeyId(auth)).thenReturn(akid);
        when(accountResolver.resolve(auth)).thenReturn(account);
        when(containerRequest.getHeaderString("Authorization")).thenReturn(auth);
        when(actionRegistry.resolve("organizations", containerRequest))
                .thenReturn("organizations:DescribeOrganization");
        // A real IAM user whose ONLY grant is conditional on being an IAM-user principal.
        when(iamService.resolveCallerContext(akid))
                .thenReturn(CallerContext.of(List.of(ALLOW_IF_IAM_USER_PRINCIPAL)));
        when(iamService.resolveCallerArn(akid))
                .thenReturn(Optional.of("arn:aws:iam::" + account + ":user/bob"));
        when(arnBuilder.buildResources(eq("organizations"), eq(containerRequest), eq("us-east-1"), eq(account)))
                .thenReturn(List.of("*"));
        when(conditionContextResolver.resolve(eq("organizations"), anyString(), eq(containerRequest)))
                .thenReturn(null);

        // No SCP ceiling (effectiveScpLevels → null) so the identity-policy Allow is the deciding factor.
        ScpProvider scp = mock(ScpProvider.class);
        when(scp.effectiveScpLevels(account)).thenReturn(null);

        newFilterWithScp(scp).filter(containerRequest);

        // aws:PrincipalArn matches arn:aws:iam::*:user/* → the conditional Allow grants access.
        verify(containerRequest, never()).abortWith(any());
        verify(arnBuilder).buildResources(eq("organizations"), eq(containerRequest), eq("us-east-1"), eq(account));
    }

    @Test
    void queryProtocolGetsXmlErrorResponse() {
        // IAM/STS/EC2/SQS/SNS/RDS/ELBv2/CFN/... — Query protocol, form-encoded body, XML response.
        Response r = IamEnforcementFilter.accessDeniedResponse(
                "iam:ListUsers", "iam", MediaType.APPLICATION_FORM_URLENCODED_TYPE);

        assertEquals(403, r.getStatus());
        assertEquals(MediaType.APPLICATION_XML_TYPE, r.getMediaType());
        String body = entityString(r);
        assertTrue(body.contains("<ErrorResponse>"), body);
        assertTrue(body.contains("<Code>AccessDenied</Code>"), body);
        assertTrue(body.contains("<Type>Sender</Type>"), body);
        assertTrue(body.contains("User is not authorized to perform: iam:ListUsers"), body);
        assertTrue(body.contains("<RequestId>"), body);
    }

    @Test
    void s3GetsS3FlavoredXmlError() {
        // S3 — credential-scope is "s3"; S3 errors are <Error>... at the root, no <ErrorResponse> wrapper.
        Response r = IamEnforcementFilter.accessDeniedResponse(
                "s3:GetObject", "s3", null);

        assertEquals(403, r.getStatus());
        assertEquals(MediaType.APPLICATION_XML_TYPE, r.getMediaType());
        String body = entityString(r);
        assertTrue(body.startsWith("<?xml"), body);
        assertTrue(body.contains("<Error>"), body);
        assertTrue(body.contains("<Code>AccessDenied</Code>"), body);
        assertTrue(body.contains("User is not authorized to perform: s3:GetObject"), body);
        // S3 errors do not have the Query <Type>Sender</Type> envelope.
        assertTrue(!body.contains("<ErrorResponse>"), body);
    }

    @Test
    void jsonProtocolGetsJsonErrorResponse() {
        // DynamoDB / Cognito / Kinesis / ... — JSON 1.0/1.1, JSON error response.
        Response r = IamEnforcementFilter.accessDeniedResponse(
                "dynamodb:PutItem", "dynamodb", MediaType.valueOf("application/x-amz-json-1.0"));

        assertEquals(403, r.getStatus());
        assertEquals(MediaType.APPLICATION_JSON_TYPE, r.getMediaType());
        String body = entityString(r);
        assertTrue(body.contains("\"__type\":\"AccessDeniedException\""), body);
        assertTrue(body.contains("User is not authorized to perform: dynamodb:PutItem"), body);
    }

    @Test
    void restJsonProtocolGetsJsonErrorResponse() {
        // Lambda / API Gateway — REST-JSON.
        Response r = IamEnforcementFilter.accessDeniedResponse(
                "lambda:InvokeFunction", "lambda", MediaType.APPLICATION_JSON_TYPE);

        assertEquals(403, r.getStatus());
        assertEquals(MediaType.APPLICATION_JSON_TYPE, r.getMediaType());
        String body = entityString(r);
        assertTrue(body.contains("\"__type\":\"AccessDeniedException\""), body);
    }

    @Test
    void formEncodedTakesPrecedenceOverNonS3Service() {
        // Even if the credentialScope isn't recognized, a form-encoded body
        // means we're talking to a Query-protocol service — XML response.
        Response r = IamEnforcementFilter.accessDeniedResponse(
                "rds:CreateDBInstance", "rds", MediaType.APPLICATION_FORM_URLENCODED_TYPE);

        assertEquals(MediaType.APPLICATION_XML_TYPE, r.getMediaType());
        assertTrue(entityString(r).contains("<ErrorResponse>"));
    }

    @Test
    void s3WithFormEncodedBodyStillGetsS3XmlShape() {
        // S3 presigned POST uploads use multipart/form-data, not x-www-form-urlencoded,
        // but if a form-encoded body ever does land here, the s3 scope must still win.
        Response r = IamEnforcementFilter.accessDeniedResponse(
                "s3:PutObject", "s3", MediaType.APPLICATION_FORM_URLENCODED_TYPE);

        String body = entityString(r);
        assertTrue(body.contains("<Error>"));
        assertTrue(!body.contains("<ErrorResponse>"));
    }

    @Test
    void unknownContentTypeFallsBackToJson() {
        // No Content-Type at all — most likely a GET against a REST-JSON service.
        Response r = IamEnforcementFilter.accessDeniedResponse(
                "kms:Decrypt", "kms", null);

        assertEquals(MediaType.APPLICATION_JSON_TYPE, r.getMediaType());
        assertTrue(entityString(r).contains("\"__type\":\"AccessDeniedException\""));
    }

    private static String entityString(Response r) {
        Object entity = r.getEntity();
        assertNotNull(entity, "response body should not be null");
        if (entity instanceof byte[] b) {
            return new String(b, StandardCharsets.UTF_8);
        }
        return entity.toString();
    }
}
