package io.floci.aws.services.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.iam.ResourcePolicyProvider.ResourcePolicy;
import io.floci.aws.services.iam.model.CallerContext;
import io.floci.aws.services.iam.model.IamRole;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoleResourceAccessAuthorizerTest {

    private static final String ACCOUNT = "111122223333";
    private static final String OTHER_ACCOUNT = "444455556666";
    private static final String ROLE = "arn:aws:iam::" + ACCOUNT + ":role/exec";
    private static final String ACTION = "lambda:InvokeFunction";
    private static final String OWN_FUNCTION = "arn:aws:lambda:us-east-1:" + ACCOUNT + ":function:sum";
    private static final String OTHER_FUNCTION = "arn:aws:lambda:eu-west-1:" + OTHER_ACCOUNT + ":function:sum";
    private static final String INVOKE_ANYTHING = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"lambda:InvokeFunction","Resource":"*"}]}""";

    private ResourcePolicyProvider functionPolicies;
    private IamService iamService;
    private RoleResourceAccessAuthorizer authorizer;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        iamService = mock(IamService.class);
        functionPolicies = mock(ResourcePolicyProvider.class);
        when(functionPolicies.getResourcePolicies(anyString(), anyString(), anyString())).thenReturn(List.of());
        Instance<ResourcePolicyProvider> providers = mock(Instance.class);
        when(providers.iterator()).thenAnswer(call -> List.of(functionPolicies).iterator());
        Instance<ScpProvider> scps = mock(Instance.class);
        IamRole role = new IamRole();
        role.setArn(ROLE);
        when(iamService.findRole(ACCOUNT, "exec")).thenReturn(Optional.of(role));
        when(iamService.resolvePrincipalContext(anyString()))
                .thenReturn(CallerContext.of(List.of(INVOKE_ANYTHING)));
        authorizer = new RoleResourceAccessAuthorizer(iamService, new IamPolicyEvaluator(new ObjectMapper()),
                providers, scps);
    }

    @Test
    void aRoleItsOwnPoliciesAllowInvokesAFunctionOfItsAccountWithoutAResourcePolicy() {
        assertThat(authorizer.refusal(ROLE, ACTION, OWN_FUNCTION, "us-east-1", Map.of()), equalTo(Optional.empty()));
    }

    @Test
    void aFunctionOfAnotherAccountNeedsItsResourcePolicyToGrantTheRole() {
        when(functionPolicies.getResourcePolicies("lambda", ACTION, OTHER_FUNCTION))
                .thenReturn(List.of(new ResourcePolicy(null, OTHER_ACCOUNT)));

        Optional<String> refusal = authorizer.refusal(ROLE, ACTION, OTHER_FUNCTION, "eu-west-1", Map.of());

        assertThat(refusal.orElseThrow(), startsWith("User: arn:aws:sts::" + ACCOUNT + ":assumed-role/exec"));
        assertThat(refusal.orElseThrow(), containsString("no resource-based policy allows the " + ACTION));
    }

    @Test
    void anExplicitDenyInTheResourcePolicyRefusesARoleItsOwnPoliciesAllow() {
        String deny = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Deny","Principal":{"AWS":"%s"},
                "Action":"lambda:InvokeFunction","Resource":"%s"}]}""".formatted(ROLE, OWN_FUNCTION);
        when(functionPolicies.getResourcePolicies("lambda", ACTION, OWN_FUNCTION))
                .thenReturn(List.of(new ResourcePolicy(deny, ACCOUNT)));

        Optional<String> refusal = authorizer.refusal(ROLE, ACTION, OWN_FUNCTION, "us-east-1", Map.of());

        assertThat(refusal.orElseThrow(), containsString("with an explicit deny in a resource-based policy"));
    }

    @Test
    void aRoleThatDoesNotExistIsRefused() {
        String missing = "arn:aws:iam::" + ACCOUNT + ":role/gone";

        Optional<String> refusal = authorizer.refusal(missing, ACTION, OWN_FUNCTION, "us-east-1", Map.of());

        assertThat(refusal.orElseThrow(), containsString("no identity-based policy allows the " + ACTION));
    }
}
