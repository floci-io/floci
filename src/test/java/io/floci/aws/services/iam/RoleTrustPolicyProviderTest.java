package io.floci.aws.services.iam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.iam.ResourcePolicyProvider.ResourcePolicy;
import io.floci.aws.services.iam.model.IamRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoleTrustPolicyProviderTest {

    private static final String OWNER = "111122223333";
    private static final String ROLE_ARN = "arn:aws:iam::" + OWNER + ":role/team/deployer";
    private static final String USER_ARN = "arn:aws:iam::" + OWNER + ":user/alice";
    private static final String TRUST = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Principal":{"AWS":"%s"},"Action":"sts:AssumeRole"}]}""".formatted(USER_ARN);

    private final ObjectMapper mapper = new ObjectMapper();
    private final IamPolicyEvaluator evaluator = new IamPolicyEvaluator(mapper);
    private IamService iamService;
    private RoleTrustPolicyProvider provider;

    @BeforeEach
    void setUp() {
        iamService = mock(IamService.class);
        when(iamService.findRole(anyString(), anyString())).thenReturn(Optional.empty());
        provider = new RoleTrustPolicyProvider(iamService, mapper);
    }

    @Test
    void assumingARoleComesWithItsTrustPolicyAndTheAccountInItsArn() throws Exception {
        storeRole(TRUST);

        List<ResourcePolicy> policies = provider.getResourcePolicies("sts", "sts:AssumeRole", ROLE_ARN);

        assertEquals(1, policies.size());
        assertEquals(OWNER, policies.getFirst().ownerAccountId());
        JsonNode statement = mapper.readTree(policies.getFirst().policyDocument()).path("Statement").get(0);
        assertEquals(USER_ARN, statement.path("Principal").path("AWS").asText());
        assertEquals("sts:AssumeRole", statement.path("Action").asText());
    }

    @Test
    void theTrustPolicyAppliesToTheRoleItIsAttachedTo() {
        // A trust policy carries no Resource element: the role it is attached to is its resource.
        storeRole(TRUST);

        List<String> documents = documents(provider.getResourcePolicies("sts", "sts:AssumeRole", ROLE_ARN));

        assertEquals(IamPolicyEvaluator.ResourcePolicyDecision.ALLOW_DIRECT_IAM_USER,
                evaluator.evaluateResourcePolicyFor(documents, RequestPrincipal.iam(USER_ARN),
                        "sts:AssumeRole", ROLE_ARN, null));
        assertEquals(IamPolicyEvaluator.ResourcePolicyDecision.NEUTRAL,
                evaluator.evaluateResourcePolicyFor(documents, RequestPrincipal.iam(USER_ARN),
                        "sts:AssumeRole", "arn:aws:iam::" + OWNER + ":role/team/other", null));
    }

    @Test
    void aRoleThatDoesNotExistStillHasItsOwner() {
        assertEquals(List.of(new ResourcePolicy(null, OWNER)),
                provider.getResourcePolicies("sts", "sts:AssumeRole", ROLE_ARN));
    }

    @Test
    void aRoleOfTheSameNameOnAnotherPathIsNotTheRoleNamed() {
        storeRole(TRUST);

        assertEquals(List.of(new ResourcePolicy(null, OWNER)), provider.getResourcePolicies("sts",
                "sts:AssumeRole", "arn:aws:iam::" + OWNER + ":role/deployer"));
    }

    @Test
    void aTrustPolicyThatCannotBeReadGrantsNothing() {
        storeRole("{\"Statement\":[{\"Effect\":\"Maybe\",\"Principal\":\"*\",\"Action\":\"sts:AssumeRole\"}]}");

        assertEquals(List.of(new ResourcePolicy(null, OWNER)),
                provider.getResourcePolicies("sts", "sts:AssumeRole", ROLE_ARN));
    }

    @Test
    void theTrustPolicyIsNotConsultedForIamActionsOnTheRole() {
        storeRole("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Deny","Principal":"*","NotAction":"sts:AssumeRole"}]}""");

        assertTrue(provider.getResourcePolicies("iam", "iam:GetRole", ROLE_ARN).isEmpty());
        assertTrue(provider.getResourcePolicies("iam", "iam:PassRole", ROLE_ARN).isEmpty());
        assertTrue(provider.getResourcePolicies("iam", ROLE_ARN).isEmpty());
    }

    @Test
    void anythingThatIsNotOneRoleIsLeftToOtherProviders() {
        assertTrue(provider.getResourcePolicies("sts", "sts:AssumeRole", "*").isEmpty());
        assertTrue(provider.getResourcePolicies("sts", "sts:AssumeRole", USER_ARN).isEmpty());
        assertTrue(provider.getResourcePolicies("sts", "sts:AssumeRole",
                "arn:aws:iam::" + OWNER + ":role/*").isEmpty());
        assertTrue(provider.getResourcePolicies("sqs", "sqs:SendMessage",
                "arn:aws:sqs:us-east-1:" + OWNER + ":orders").isEmpty());
    }

    private void storeRole(String trustPolicy) {
        IamRole role = new IamRole("AROAEXAMPLE", "deployer", "/team/", ROLE_ARN, trustPolicy);
        when(iamService.findRole(OWNER, "deployer")).thenReturn(Optional.of(role));
    }

    private static List<String> documents(List<ResourcePolicy> policies) {
        return policies.stream().map(ResourcePolicy::policyDocument).toList();
    }
}
