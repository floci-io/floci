package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.iam.AssumeRolePolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.ResourcePolicyProvider.ResourcePolicy;
import io.github.hectorvent.floci.services.iam.ScpProvider;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.services.lambda.LambdaResourcePolicyProvider;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LambdaTaskAuthorizerTest {

    private static final String SFN_ACCOUNT = "111122223333";
    private static final String FUNCTION_ACCOUNT = "444455556666";
    private static final String ROLE = "arn:aws:iam::" + SFN_ACCOUNT + ":role/service-role/sfn-exec";
    /** The role's session as the Step Functions Developer Guide writes it in an access denied message. */
    private static final String SESSION = "arn:aws:sts::" + SFN_ACCOUNT + ":assumed-role/sfn-exec";
    private static final String FUNCTION = "arn:aws:lambda:us-east-1:" + FUNCTION_ACCOUNT + ":function:worker";
    private static final String ALIAS = FUNCTION + ":live";
    private static final String OWN_FUNCTION = "arn:aws:lambda:us-east-1:" + SFN_ACCOUNT + ":function:worker";
    private static final String INVOKE_ANYTHING = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"lambda:InvokeFunction","Resource":"*"}]}""";
    private static final String READ_OBJECTS = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"s3:GetObject","Resource":"*"}]}""";
    /** A role a task's Credentials name, in the function's account as the cross-account tutorial sets it up. */
    private static final String TASK_ROLE = "arn:aws:iam::" + FUNCTION_ACCOUNT + ":role/LambdaRole";
    private static final String OWN_TASK_ROLE = "arn:aws:iam::" + SFN_ACCOUNT + ":role/LambdaRole";
    private static final String STATE_MACHINE = "arn:aws:states:us-east-1:" + SFN_ACCOUNT + ":stateMachine:orders";
    private static final String ASSUME_ANY_ROLE = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"sts:AssumeRole","Resource":"*"}]}""";

    private IamService iamService;
    private LambdaResourcePolicyProvider functionPolicies;
    private Instance<ScpProvider> scpProvider;
    private LambdaTaskAuthorizer authorizer;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        iamService = mock(IamService.class);
        functionPolicies = mock(LambdaResourcePolicyProvider.class);
        scpProvider = mock(Instance.class);
        when(functionPolicies.getResourcePolicies(anyString(), anyString()))
                .thenReturn(List.of(new ResourcePolicy(null, FUNCTION_ACCOUNT)));
        when(functionPolicies.getResourcePolicies("lambda", OWN_FUNCTION))
                .thenReturn(List.of(new ResourcePolicy(null, SFN_ACCOUNT)));
        ObjectMapper mapper = new ObjectMapper();
        IamPolicyEvaluator evaluator = new IamPolicyEvaluator(mapper);
        authorizer = new LambdaTaskAuthorizer(iamService, evaluator, new AssumeRolePolicyEvaluator(mapper, evaluator),
                functionPolicies, scpProvider);
    }

    @Test
    void aFunctionPolicyGrantingTheRoleLetsTheRoleInvoke() {
        roleWithPolicies(INVOKE_ANYTHING);
        functionPolicy(FUNCTION, FUNCTION_ACCOUNT, grant("{\"AWS\":\"" + ROLE + "\"}", FUNCTION));

        assertEquals(Optional.empty(), authorizer.refusal(ROLE, FUNCTION));
    }

    @Test
    void aGrantToTheRolesAccountCoversTheRole() {
        roleWithPolicies(INVOKE_ANYTHING);
        functionPolicy(FUNCTION, FUNCTION_ACCOUNT, grant("{\"AWS\":\"arn:aws:iam::" + SFN_ACCOUNT + ":root\"}", FUNCTION));

        assertEquals(Optional.empty(), authorizer.refusal(ROLE, FUNCTION));
    }

    @Test
    void aFunctionWhosePolicyGrantsNothingRefusesTheRole() {
        roleWithPolicies(INVOKE_ANYTHING);

        assertEquals(denied(FUNCTION, "because no resource-based policy allows the lambda:InvokeFunction action"),
                authorizer.refusal(ROLE, FUNCTION));
    }

    @Test
    void aGrantFromTheFunctionIsNotEnoughWithoutTheRolesOwnPermission() {
        roleWithPolicies(READ_OBJECTS);
        functionPolicy(FUNCTION, FUNCTION_ACCOUNT, grant("{\"AWS\":\"" + ROLE + "\"}", FUNCTION));

        assertEquals(denied(FUNCTION, "because no identity-based policy allows the lambda:InvokeFunction action"),
                authorizer.refusal(ROLE, FUNCTION));
    }

    @Test
    void aGrantToTheRolesAccountIsTheFunctionsHalfOnlySoTheRoleNeedsItsOwnPermission() {
        roleWithPolicies(READ_OBJECTS);
        functionPolicy(FUNCTION, FUNCTION_ACCOUNT, grant("{\"AWS\":\"arn:aws:iam::" + SFN_ACCOUNT + ":root\"}", FUNCTION));

        assertEquals(denied(FUNCTION, "because no identity-based policy allows the lambda:InvokeFunction action"),
                authorizer.refusal(ROLE, FUNCTION));
    }

    @Test
    void aDenyInTheRolesPolicyRefusesAGrantedFunction() {
        roleWithPolicies(INVOKE_ANYTHING, """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Deny","Action":"lambda:InvokeFunction","Resource":"%s"}]}""".formatted(FUNCTION));
        functionPolicy(FUNCTION, FUNCTION_ACCOUNT, grant("{\"AWS\":\"" + ROLE + "\"}", FUNCTION));

        assertEquals(denied(FUNCTION, "with an explicit deny in an identity-based policy"),
                authorizer.refusal(ROLE, FUNCTION));
    }

    @Test
    void aDenyInTheFunctionPolicyRefusesTheRole() {
        roleWithPolicies(INVOKE_ANYTHING);
        functionPolicy(FUNCTION, FUNCTION_ACCOUNT, """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Principal":{"AWS":"%1$s"},"Action":"lambda:InvokeFunction","Resource":"%2$s"},
                  {"Effect":"Deny","Principal":{"AWS":"%1$s"},"Action":"lambda:InvokeFunction","Resource":"%2$s"}]}"""
                .formatted(ROLE, FUNCTION));

        assertEquals(denied(FUNCTION, "with an explicit deny in a resource-based policy"),
                authorizer.refusal(ROLE, FUNCTION));
    }

    @Test
    void aGrantOnTheFunctionDoesNotCoverACallToItsAlias() {
        roleWithPolicies(INVOKE_ANYTHING);
        functionPolicy(ALIAS, FUNCTION_ACCOUNT, grant("{\"AWS\":\"" + ROLE + "\"}", FUNCTION));

        assertEquals(denied(ALIAS, "because no resource-based policy allows the lambda:InvokeFunction action"),
                authorizer.refusal(ROLE, ALIAS));

        functionPolicy(ALIAS, FUNCTION_ACCOUNT, grant("{\"AWS\":\"" + ROLE + "\"}", ALIAS));
        assertEquals(Optional.empty(), authorizer.refusal(ROLE, ALIAS));
    }

    @Test
    void aGrantConditionedOnThePrincipalArnMatchesTheRole() {
        roleWithPolicies(INVOKE_ANYTHING);
        functionPolicy(FUNCTION, FUNCTION_ACCOUNT, """
                {"Version":"2012-10-17","Statement":[{
                  "Effect":"Allow","Principal":{"AWS":"arn:aws:iam::%s:root"},"Action":"lambda:InvokeFunction",
                  "Resource":"%s","Condition":{"ArnLike":{"aws:PrincipalArn":"arn:aws:iam::*:role/service-role/*"}}}]}"""
                .formatted(SFN_ACCOUNT, FUNCTION));

        assertEquals(Optional.empty(), authorizer.refusal(ROLE, FUNCTION));
    }

    @Test
    void inTheRolesOwnAccountTheRolesPermissionIsEnough() {
        roleWithPolicies(INVOKE_ANYTHING);

        assertEquals(Optional.empty(), authorizer.refusal(ROLE, OWN_FUNCTION));
    }

    @Test
    void inTheRolesOwnAccountTheFunctionsGrantIsEnough() {
        roleWithPolicies(READ_OBJECTS);
        functionPolicy(OWN_FUNCTION, SFN_ACCOUNT, grant("{\"AWS\":\"" + ROLE + "\"}", OWN_FUNCTION));

        assertEquals(Optional.empty(), authorizer.refusal(ROLE, OWN_FUNCTION));
    }

    @Test
    void inTheRolesOwnAccountARoleWithoutPermissionAndNoGrantIsRefused() {
        roleWithPolicies(READ_OBJECTS);

        assertEquals(denied(OWN_FUNCTION, "because no identity-based policy allows the lambda:InvokeFunction action"),
                authorizer.refusal(ROLE, OWN_FUNCTION));
    }

    @Test
    void aRoleThatDoesNotExistIsRefused() {
        when(iamService.findRole(SFN_ACCOUNT, "sfn-exec")).thenReturn(Optional.empty());
        functionPolicy(FUNCTION, FUNCTION_ACCOUNT, grant("{\"AWS\":\"" + ROLE + "\"}", FUNCTION));

        assertEquals(denied(FUNCTION, "because no identity-based policy allows the lambda:InvokeFunction action"),
                authorizer.refusal(ROLE, FUNCTION));
    }

    @Test
    void aRoleArnWithAnotherPathIsNotTheRoleAGrantNames() {
        roleWithPolicies(INVOKE_ANYTHING);
        String otherPath = "arn:aws:iam::" + SFN_ACCOUNT + ":role/other/sfn-exec";
        functionPolicy(FUNCTION, FUNCTION_ACCOUNT, grant("{\"AWS\":\"" + otherPath + "\"}", FUNCTION));

        assertEquals(denied(FUNCTION, "because no identity-based policy allows the lambda:InvokeFunction action"),
                authorizer.refusal(otherPath, FUNCTION));
    }

    @Test
    void aRoleArnInAnotherPartitionNamesTheSameRole() {
        roleWithPolicies(INVOKE_ANYTHING);
        functionPolicy(FUNCTION, FUNCTION_ACCOUNT, grant("{\"AWS\":\"" + ROLE + "\"}", FUNCTION));

        assertEquals(Optional.empty(),
                authorizer.refusal("arn:aws-cn:iam::" + SFN_ACCOUNT + ":role/service-role/sfn-exec", FUNCTION));
    }

    @Test
    void aServiceControlPolicyDenyingTheCallRefusesIt() {
        roleWithPolicies(INVOKE_ANYTHING);
        functionPolicy(FUNCTION, FUNCTION_ACCOUNT, grant("{\"AWS\":\"" + ROLE + "\"}", FUNCTION));
        scps("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"*","Resource":"*"},
                  {"Effect":"Deny","Action":"lambda:InvokeFunction","Resource":"*"}]}""");

        assertEquals(denied(FUNCTION, "with an explicit deny in a service control policy"),
                authorizer.refusal(ROLE, FUNCTION));
    }

    @Test
    void aServiceControlPolicyThatDoesNotAllowTheCallRefusesIt() {
        roleWithPolicies(INVOKE_ANYTHING);
        functionPolicy(FUNCTION, FUNCTION_ACCOUNT, grant("{\"AWS\":\"" + ROLE + "\"}", FUNCTION));
        scps("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:*","Resource":"*"}]}""");

        assertEquals(denied(FUNCTION, "because no service control policy allows the lambda:InvokeFunction action"),
                authorizer.refusal(ROLE, FUNCTION));
    }

    @Test
    void aPermissionsBoundaryThatDoesNotAllowTheCallRefusesIt() {
        roleWithBoundary(INVOKE_ANYTHING, READ_OBJECTS);
        functionPolicy(FUNCTION, FUNCTION_ACCOUNT, grant("{\"AWS\":\"" + ROLE + "\"}", FUNCTION));

        assertEquals(denied(FUNCTION, "because no permissions boundary allows the lambda:InvokeFunction action"),
                authorizer.refusal(ROLE, FUNCTION));
    }

    @Test
    void aPermissionsBoundaryDenyingTheCallRefusesIt() {
        roleWithBoundary(INVOKE_ANYTHING, """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"*","Resource":"*"},
                  {"Effect":"Deny","Action":"lambda:InvokeFunction","Resource":"*"}]}""");
        functionPolicy(FUNCTION, FUNCTION_ACCOUNT, grant("{\"AWS\":\"" + ROLE + "\"}", FUNCTION));

        assertEquals(denied(FUNCTION, "with an explicit deny in a permissions boundary"),
                authorizer.refusal(ROLE, FUNCTION));
    }

    @Test
    void aRoleInAnotherAccountTrustingTheExecutionRoleCanBeAssumed() {
        roleWithPolicies(ASSUME_ANY_ROLE);
        taskRole(TASK_ROLE, trust("{\"AWS\":\"" + ROLE + "\"}"));

        assertEquals(Optional.empty(), authorizer.assumeRefusal(ROLE, TASK_ROLE, STATE_MACHINE));
    }

    @Test
    void aRoleInAnotherAccountNeedsTheExecutionRolesOwnPermissionToo() {
        roleWithPolicies(READ_OBJECTS);
        taskRole(TASK_ROLE, trust("{\"AWS\":\"" + ROLE + "\"}"));

        assertEquals(assumeDenied(TASK_ROLE, "because no identity-based policy allows the sts:AssumeRole action"),
                authorizer.assumeRefusal(ROLE, TASK_ROLE, STATE_MACHINE));
    }

    @Test
    void aRoleWhoseTrustPolicyDoesNotNameTheExecutionRoleCannotBeAssumed() {
        roleWithPolicies(ASSUME_ANY_ROLE);
        taskRole(TASK_ROLE, trust("{\"AWS\":\"arn:aws:iam::" + SFN_ACCOUNT + ":role/someone-else\"}"));

        assertEquals(assumeDenied(TASK_ROLE, "because no role trust policy allows the sts:AssumeRole action"),
                authorizer.assumeRefusal(ROLE, TASK_ROLE, STATE_MACHINE));
    }

    @Test
    void aDenyInTheTrustPolicyRefusesTheExecutionRole() {
        roleWithPolicies(ASSUME_ANY_ROLE);
        taskRole(TASK_ROLE, """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Principal":{"AWS":"%1$s"},"Action":"sts:AssumeRole"},
                  {"Effect":"Deny","Principal":{"AWS":"%1$s"},"Action":"sts:AssumeRole"}]}""".formatted(ROLE));

        assertEquals(assumeDenied(TASK_ROLE, "with an explicit deny in the role trust policy"),
                authorizer.assumeRefusal(ROLE, TASK_ROLE, STATE_MACHINE));
    }

    @Test
    void inItsOwnAccountATrustPolicyNamingTheExecutionRoleIsEnough() {
        roleWithPolicies(READ_OBJECTS);
        taskRole(OWN_TASK_ROLE, trust("{\"AWS\":\"" + ROLE + "\"}"));

        assertEquals(Optional.empty(), authorizer.assumeRefusal(ROLE, OWN_TASK_ROLE, STATE_MACHINE));
    }

    @Test
    void inItsOwnAccountARoleWhoseTrustPolicyDoesNotNameTheExecutionRoleCannotBeAssumed() {
        roleWithPolicies(ASSUME_ANY_ROLE);
        taskRole(OWN_TASK_ROLE, trust("{\"Service\":\"states.amazonaws.com\"}"));

        assertEquals(assumeDenied(OWN_TASK_ROLE, "because no role trust policy allows the sts:AssumeRole action"),
                authorizer.assumeRefusal(ROLE, OWN_TASK_ROLE, STATE_MACHINE));
    }

    @Test
    void inItsOwnAccountATrustPolicyNamingTheAccountDelegatesToTheExecutionRolesPolicies() {
        roleWithPolicies(READ_OBJECTS);
        taskRole(OWN_TASK_ROLE, trust("{\"AWS\":\"arn:aws:iam::" + SFN_ACCOUNT + ":root\"}"));

        assertEquals(assumeDenied(OWN_TASK_ROLE, "because no identity-based policy allows the sts:AssumeRole action"),
                authorizer.assumeRefusal(ROLE, OWN_TASK_ROLE, STATE_MACHINE));

        roleWithPolicies(ASSUME_ANY_ROLE);
        assertEquals(Optional.empty(), authorizer.assumeRefusal(ROLE, OWN_TASK_ROLE, STATE_MACHINE));
    }

    @Test
    void theTrustPolicyCanRequireTheStateMachineAsTheExternalId() {
        roleWithPolicies(ASSUME_ANY_ROLE);
        taskRole(TASK_ROLE, """
                {"Version":"2012-10-17","Statement":[{
                  "Effect":"Allow","Principal":{"AWS":"%s"},"Action":"sts:AssumeRole",
                  "Condition":{"StringEquals":{"sts:ExternalId":"%s"}}}]}""".formatted(ROLE, STATE_MACHINE));

        assertEquals(Optional.empty(), authorizer.assumeRefusal(ROLE, TASK_ROLE, STATE_MACHINE));
        assertEquals(assumeDenied(TASK_ROLE, "because no role trust policy allows the sts:AssumeRole action"),
                authorizer.assumeRefusal(ROLE, TASK_ROLE, STATE_MACHINE.replace(":orders", ":other")));
    }

    @Test
    void theTrustPolicyCanRequireAnAssumedRoleCaller() {
        roleWithPolicies(ASSUME_ANY_ROLE);
        taskRole(TASK_ROLE, """
                {"Version":"2012-10-17","Statement":[{
                  "Effect":"Allow","Principal":{"AWS":"%s"},"Action":"sts:AssumeRole",
                  "Condition":{"StringEquals":{"aws:PrincipalType":"AssumedRole"}}}]}""".formatted(ROLE));

        assertEquals(Optional.empty(), authorizer.assumeRefusal(ROLE, TASK_ROLE, STATE_MACHINE));
    }

    @Test
    void theTrustPolicyCannotMatchTheSessionsOwnIdOnThisPath() {
        roleWithPolicies(ASSUME_ANY_ROLE);
        taskRole(TASK_ROLE, """
                {"Version":"2012-10-17","Statement":[{
                  "Effect":"Allow","Principal":{"AWS":"%s"},"Action":"sts:AssumeRole",
                  "Condition":{"StringLike":{"aws:userid":"*"}}}]}""".formatted(ROLE));

        // Step Functions mints the session without a name, and a session's aws:userid is
        // role-id:session-name, so the key is absent and a condition on it matches nothing.
        assertEquals(assumeDenied(TASK_ROLE, "because no role trust policy allows the sts:AssumeRole action"),
                authorizer.assumeRefusal(ROLE, TASK_ROLE, STATE_MACHINE));
    }

    @Test
    void aRoleThatDoesNotExistCannotBeAssumedAndNoPolicyIsNamed() {
        roleWithPolicies(ASSUME_ANY_ROLE);
        when(iamService.findRole(FUNCTION_ACCOUNT, "LambdaRole")).thenReturn(Optional.empty());

        assertEquals(Optional.of("User: " + SESSION + " is not authorized to perform: sts:AssumeRole on resource: "
                + TASK_ROLE), authorizer.assumeRefusal(ROLE, TASK_ROLE, STATE_MACHINE));
    }

    @Test
    void aServiceControlPolicyDenyingAssumeRoleRefusesIt() {
        roleWithPolicies(ASSUME_ANY_ROLE);
        taskRole(TASK_ROLE, trust("{\"AWS\":\"" + ROLE + "\"}"));
        scps("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"*","Resource":"*"},
                  {"Effect":"Deny","Action":"sts:AssumeRole","Resource":"*"}]}""");

        assertEquals(assumeDenied(TASK_ROLE, "with an explicit deny in a service control policy"),
                authorizer.assumeRefusal(ROLE, TASK_ROLE, STATE_MACHINE));
    }

    @Test
    void aPermissionsBoundaryLimitsATrustPolicyNamingTheExecutionRole() {
        roleWithBoundary(ASSUME_ANY_ROLE, READ_OBJECTS);
        taskRole(OWN_TASK_ROLE, trust("{\"AWS\":\"" + ROLE + "\"}"));

        assertEquals(assumeDenied(OWN_TASK_ROLE, "because no permissions boundary allows the sts:AssumeRole action"),
                authorizer.assumeRefusal(ROLE, OWN_TASK_ROLE, STATE_MACHINE));
    }

    private static Optional<String> denied(String functionArn, String reason) {
        return Optional.of("User: " + SESSION + " is not authorized to perform: lambda:InvokeFunction on resource: "
                + functionArn + " " + reason);
    }

    private static Optional<String> assumeDenied(String roleArn, String reason) {
        return Optional.of("User: " + SESSION + " is not authorized to perform: sts:AssumeRole on resource: "
                + roleArn + " " + reason);
    }

    private void taskRole(String roleArn, String trustPolicy) {
        IamRole role = new IamRole();
        role.setArn(roleArn);
        role.setAssumeRolePolicyDocument(trustPolicy);
        when(iamService.findRole(roleArn.split(":")[4], "LambdaRole")).thenReturn(Optional.of(role));
    }

    private static String trust(String principal) {
        return """
                {"Version":"2012-10-17","Statement":[{
                  "Effect":"Allow","Principal":%s,"Action":"sts:AssumeRole"}]}""".formatted(principal);
    }

    private void roleWithPolicies(String... documents) {
        role(CallerContext.of(List.of(documents)));
    }

    private void roleWithBoundary(String policy, String boundary) {
        role(new CallerContext(List.of(policy), null, boundary));
    }

    private void role(CallerContext context) {
        IamRole role = new IamRole();
        role.setArn(ROLE);
        when(iamService.findRole(SFN_ACCOUNT, "sfn-exec")).thenReturn(Optional.of(role));
        when(iamService.resolvePrincipalContext(anyString())).thenReturn(context);
    }

    private void scps(String document) {
        ScpProvider provider = mock(ScpProvider.class);
        when(provider.effectiveScpLevels(SFN_ACCOUNT)).thenReturn(List.of(List.of(document)));
        when(scpProvider.isResolvable()).thenReturn(true);
        when(scpProvider.get()).thenReturn(provider);
    }

    private void functionPolicy(String functionArn, String owner, String document) {
        when(functionPolicies.getResourcePolicies("lambda", functionArn))
                .thenReturn(List.of(new ResourcePolicy(document, owner)));
    }

    private static String grant(String principal, String resource) {
        return """
                {"Version":"2012-10-17","Statement":[{
                  "Effect":"Allow","Principal":%s,"Action":"lambda:InvokeFunction","Resource":"%s"}]}"""
                .formatted(principal, resource);
    }
}
