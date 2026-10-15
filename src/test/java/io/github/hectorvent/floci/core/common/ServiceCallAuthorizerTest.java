package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.Decision;
import io.github.hectorvent.floci.services.iam.ResourcePolicyProvider;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ServiceCallAuthorizerTest {

    private static final String OWNER = "111122223333";
    private static final String OTHER = "444455556666";
    private static final String TOPIC = "arn:aws:sns:us-east-1:" + OWNER + ":events";
    private static final String CONFIG_SET = "arn:aws:ses:us-east-1:" + OWNER + ":configuration-set/my-set";

    /** The topic policy the SES Developer Guide gives for an SNS event destination. */
    private static final String SES_GUIDE_POLICY = """
            {"Version":"2012-10-17","Id":"notification-policy","Statement":[{
              "Effect":"Allow","Principal":{"Service":"ses.amazonaws.com"},"Action":"sns:Publish",
              "Resource":"%s",
              "Condition":{"StringEquals":{"AWS:SourceAccount":"%s","AWS:SourceArn":"%s"}}}]}"""
            .formatted(TOPIC, OWNER, CONFIG_SET);

    /** The policy a new topic starts with. */
    private static final String DEFAULT_TOPIC_POLICY = """
            {"Version":"2008-10-17","Id":"__default_policy_ID","Statement":[{
              "Sid":"__default_statement_ID","Effect":"Allow","Principal":{"AWS":"*"},
              "Action":["SNS:GetTopicAttributes","SNS:SetTopicAttributes","SNS:AddPermission",
                "SNS:RemovePermission","SNS:DeleteTopic","SNS:Subscribe","SNS:ListSubscriptionsByTopic",
                "SNS:Publish"],
              "Resource":"%s","Condition":{"StringEquals":{"AWS:SourceOwner":"%s"}}}]}"""
            .formatted(TOPIC, OWNER);

    private EmulatorConfig.IamServiceConfig iamConfig;
    private final List<ResourcePolicyProvider> providers = new ArrayList<>();
    private final List<String> scopesAsked = new ArrayList<>();
    private ServiceCallAuthorizer authorizer;

    @BeforeEach
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        iamConfig = mock(EmulatorConfig.IamServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.iam()).thenReturn(iamConfig);
        when(iamConfig.enforcementEnabled()).thenReturn(true);
        @SuppressWarnings("unchecked")
        Instance<ResourcePolicyProvider> instance = mock(Instance.class);
        when(instance.isUnsatisfied()).thenAnswer(invocation -> providers.isEmpty());
        when(instance.iterator()).thenAnswer(invocation -> providers.iterator());
        authorizer = new ServiceCallAuthorizer(config, new IamPolicyEvaluator(new ObjectMapper()), instance);
    }

    private void topicPolicy(String policy) {
        providers.add((scope, arn) -> {
            scopesAsked.add(scope);
            return TOPIC.equals(arn) ? List.of(new ResourcePolicyProvider.ResourcePolicy(policy, OWNER)) : List.of();
        });
    }

    private Decision sesPublish(String sourceArn, String sourceAccount) {
        return authorizer.authorize("ses", "sns:Publish", TOPIC, sourceArn, sourceAccount);
    }

    @Test
    void withEnforcementOffEveryCallIsAllowed() {
        when(iamConfig.enforcementEnabled()).thenReturn(false);

        assertEquals(Decision.ALLOW, sesPublish(CONFIG_SET, OWNER));
    }

    @Test
    void theSesGuidesTopicPolicyAdmitsTheConfigurationSetItNames() {
        topicPolicy(SES_GUIDE_POLICY);

        assertEquals(Decision.ALLOW, sesPublish(CONFIG_SET, OWNER));
        assertEquals(List.of("sns"), scopesAsked);
    }

    @Test
    void aGrantNamingOneConfigurationSetDoesNotAdmitAnother() {
        topicPolicy(SES_GUIDE_POLICY);

        assertEquals(Decision.DENY,
                sesPublish("arn:aws:ses:us-east-1:" + OWNER + ":configuration-set/other-set", OWNER));
        assertEquals(Decision.DENY,
                sesPublish("arn:aws:ses:us-east-1:" + OTHER + ":configuration-set/my-set", OTHER));
    }

    @Test
    void aGrantToAnotherServiceDoesNotAdmitSes() {
        topicPolicy(SES_GUIDE_POLICY.replace("ses.amazonaws.com", "s3.amazonaws.com"));

        assertEquals(Decision.DENY, sesPublish(CONFIG_SET, OWNER));
    }

    @Test
    void theDefaultTopicPolicyAdmitsAServiceActingForTheOwnersAccountOnly() {
        topicPolicy(DEFAULT_TOPIC_POLICY);

        assertEquals(Decision.ALLOW, sesPublish(CONFIG_SET, OWNER));
        assertEquals(Decision.DENY,
                sesPublish("arn:aws:ses:us-east-1:" + OTHER + ":configuration-set/my-set", OTHER));
    }

    @Test
    void theDefaultTopicPolicyAdmitsNoServiceThatSetsOnlySourceAccount() {
        topicPolicy(DEFAULT_TOPIC_POLICY);

        assertEquals(Decision.DENY,
                authorizer.authorize("s3", "sns:Publish", TOPIC, "arn:aws:s3:::bucket", OWNER));
    }

    @Test
    void aDenyInTheTopicPolicyWins() {
        topicPolicy("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Principal":{"Service":"ses.amazonaws.com"},"Action":"sns:Publish","Resource":"%1$s"},
                  {"Effect":"Deny","Principal":"*","Action":"sns:Publish","Resource":"%1$s",
                   "Condition":{"StringEquals":{"aws:PrincipalServiceName":"ses.amazonaws.com"}}}]}"""
                .formatted(TOPIC));

        assertEquals(Decision.DENY, sesPublish(CONFIG_SET, OWNER));
    }

    @Test
    void aTargetWithNoPolicyIsRefused() {
        assertEquals(Decision.DENY, sesPublish(CONFIG_SET, OWNER));

        topicPolicy(null);
        assertEquals(Decision.DENY, sesPublish(CONFIG_SET, OWNER));
    }

    @Test
    void aGrantConditionedOnAnIamPrincipalDoesNotMatchAServiceCall() {
        topicPolicy("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Principal":{"AWS":"*"},"Action":"sns:Publish","Resource":"%s",
                   "Condition":{"StringLike":{"aws:PrincipalArn":"*"}}}]}""".formatted(TOPIC));

        assertEquals(Decision.DENY, sesPublish(CONFIG_SET, OWNER));
    }
}
