package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cognito.CognitoService;
import io.github.hectorvent.floci.services.cognito.model.UserPool;
import io.github.hectorvent.floci.services.cognito.model.UserPoolClient;
import io.github.hectorvent.floci.services.cognito.model.UserPoolDomain;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Cognito CFN provisioner in isolation: one mocked service. Every case asserts the exact
 * physical id and the exact {@code Fn::GetAtt} attribute keys, since an unmapped type still
 * reports CREATE_COMPLETE through the dispatcher's stub arm.
 */
class CognitoCfnProvisionerTest {

    private static final String TYPE = "AWS::Cognito::UserPoolDomain";
    private static final String USER_POOL = "AWS::Cognito::UserPool";
    private static final String USER_POOL_CLIENT = "AWS::Cognito::UserPoolClient";
    private static final String CLIENT_ID = "1h57kf5cpq17m0eml12EXAMPLE";
    private static final String POOL_ARN = "arn:aws:cognito-idp:us-east-1:000000000000:userpool/us-east-1_AbCdEfGhI";
    private static final String ISSUER = "http://localhost:4566/us-east-1_AbCdEfGhI";
    private static final String REGION = "us-east-1";
    private static final String POOL_ID = "us-east-1_AbCdEfGhI";
    private static final String DOMAIN = "auth.example.com";
    private static final String CERTIFICATE_ARN =
            "arn:aws:acm:us-east-1:000000000000:certificate/11111111-2222-3333-4444-555555555555";
    private static final String RENEWED_CERTIFICATE_ARN =
            "arn:aws:acm:us-east-1:000000000000:certificate/99999999-2222-3333-4444-555555555555";
    private static final String CLOUDFRONT = "d1234567890abc.cloudfront.net";

    private final CognitoService cognito = mock(CognitoService.class);
    private final CognitoCfnProvisioner provisioner = new CognitoCfnProvisioner(cognito);
    private final ObjectMapper mapper = new ObjectMapper();

    private ProvisionContext ctx() {
        return ctx(null);
    }

    private ProvisionContext ctx(String priorPhysicalId) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        // resolveStringList delegates to the real engine method; for the literal arrays these
        // tests use it just walks the array and calls resolve(...) per element, stubbed above.
        when(engine.resolveStringList(any())).thenCallRealMethod();
        return new ProvisionContext(engine, REGION, "000000000000", "my-stack", priorPhysicalId);
    }

    private static StackResource resource() {
        StackResource r = new StackResource();
        r.setLogicalId("Domain");
        r.setResourceType(TYPE);
        r.setAttributes(new HashMap<>());
        return r;
    }

    private static StackResource resource(String type, String logicalId) {
        StackResource r = resource();
        r.setLogicalId(logicalId);
        r.setResourceType(type);
        return r;
    }

    private static StackResource resource(String physicalId, Map<String, String> attributes) {
        StackResource r = resource();
        r.setPhysicalId(physicalId);
        r.setAttributes(new HashMap<>(attributes));
        return r;
    }

    private static UserPool pool(String id, String name) {
        UserPool p = new UserPool();
        p.setId(id);
        p.setName(name);
        p.setArn(POOL_ARN);
        return p;
    }

    private static UserPoolClient client(String name, String secret) {
        return client(CLIENT_ID, POOL_ID, name, secret);
    }

    private static UserPoolClient client(String id, String poolId, String name, String secret) {
        UserPoolClient c = new UserPoolClient();
        c.setClientId(id);
        c.setUserPoolId(poolId);
        c.setClientName(name);
        c.setClientSecret(secret);
        c.setGenerateSecret(secret != null);
        return c;
    }

    private void verifyNoClientCreate() {
        verify(cognito, never()).createUserPoolClient(any(), any(), anyBoolean(), anyBoolean(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    private void verifyNoClientUpdate() {
        verify(cognito, never()).updateUserPoolClient(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    private void stubClientCreate(UserPoolClient created) {
        when(cognito.createUserPoolClient(any(), any(), anyBoolean(), anyBoolean(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(created);
    }

    private void stubClientUpdate(UserPoolClient updated) {
        when(cognito.updateUserPoolClient(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(updated);
    }

    private static UserPoolDomain domain(String domain, String userPoolId, String cloudFront) {
        UserPoolDomain d = new UserPoolDomain();
        d.setDomain(domain);
        d.setUserPoolId(userPoolId);
        d.setCloudFrontDistribution(cloudFront);
        return d;
    }

    private ObjectNode customDomainProps(String certificateArn) {
        ObjectNode props = mapper.createObjectNode()
                .put("Domain", DOMAIN)
                .put("UserPoolId", POOL_ID)
                .put("ManagedLoginVersion", 2);
        props.putObject("CustomDomainConfig").put("CertificateArn", certificateArn);
        return props;
    }

    @Test
    void customDomainSetsDomainAsPhysicalIdAndCloudFrontAttribute() {
        when(cognito.createUserPoolDomain(DOMAIN, POOL_ID, Map.of("CertificateArn", CERTIFICATE_ARN), 2))
                .thenReturn(domain(DOMAIN, POOL_ID, CLOUDFRONT));
        StackResource r = resource();

        provisioner.provision(r, customDomainProps(CERTIFICATE_ARN), ctx());

        assertEquals(DOMAIN, r.getPhysicalId());
        assertEquals(Map.of("UserPoolId", POOL_ID, "CloudFrontDistribution", CLOUDFRONT), r.getAttributes());
    }

    @Test
    void prefixDomainHasAnEmptyCloudFrontDistribution() {
        when(cognito.createUserPoolDomain(eq("my-prefix"), eq(POOL_ID), isNull(), isNull()))
                .thenReturn(domain("my-prefix", POOL_ID, null));
        StackResource r = resource();

        provisioner.provision(r, mapper.createObjectNode().put("Domain", "my-prefix").put("UserPoolId", POOL_ID), ctx());

        assertEquals("my-prefix", r.getPhysicalId());
        assertEquals(Map.of("UserPoolId", POOL_ID, "CloudFrontDistribution", ""), r.getAttributes());
    }

    @Test
    void customDomainConfigPassesEveryFieldThrough() {
        when(cognito.createUserPoolDomain(any(), any(), any(), any())).thenReturn(domain(DOMAIN, POOL_ID, CLOUDFRONT));
        ObjectNode props = mapper.createObjectNode().put("Domain", DOMAIN).put("UserPoolId", POOL_ID);
        props.putObject("CustomDomainConfig")
                .put("CertificateArn", CERTIFICATE_ARN)
                .put("SecurityPolicy", "TLS_V1_2_2021");

        provisioner.provision(resource(), props, ctx());

        verify(cognito).createUserPoolDomain(DOMAIN, POOL_ID,
                Map.of("CertificateArn", CERTIFICATE_ARN, "SecurityPolicy", "TLS_V1_2_2021"), null);
    }

    @Test
    void customDomainConfigSkipsNullValues() {
        // A JSON null must not reach the service as the text "null", where it would pass for an ARN.
        when(cognito.createUserPoolDomain(any(), any(), any(), any())).thenReturn(domain(DOMAIN, POOL_ID, CLOUDFRONT));
        ObjectNode props = mapper.createObjectNode().put("Domain", DOMAIN).put("UserPoolId", POOL_ID);
        props.putObject("CustomDomainConfig")
                .putNull("CertificateArn")
                .put("SecurityPolicy", "TLS_V1_2_2021");

        provisioner.provision(resource(), props, ctx());

        verify(cognito).createUserPoolDomain(DOMAIN, POOL_ID, Map.of("SecurityPolicy", "TLS_V1_2_2021"), null);
    }

    @Test
    void requiresDomainAndUserPoolId() {
        assertThrows(IllegalArgumentException.class, () -> provisioner.provision(
                resource(), mapper.createObjectNode().put("UserPoolId", POOL_ID), ctx()));
        assertThrows(IllegalArgumentException.class, () -> provisioner.provision(
                resource(), mapper.createObjectNode().put("Domain", DOMAIN), ctx()));

        verify(cognito, never()).createUserPoolDomain(any(), any(), any(), any());
    }

    @Test
    void rejectsANonIntegerManagedLoginVersion() {
        ObjectNode props = customDomainProps(CERTIFICATE_ARN).put("ManagedLoginVersion", "two");

        assertThrows(IllegalArgumentException.class, () -> provisioner.provision(resource(), props, ctx()));

        verify(cognito, never()).createUserPoolDomain(any(), any(), any(), any());
    }

    @Test
    void updateWithUnchangedDomainAndPoolUpdatesInPlace() {
        when(cognito.describeUserPoolDomain(DOMAIN)).thenReturn(domain(DOMAIN, POOL_ID, CLOUDFRONT));
        when(cognito.updateUserPoolDomain(DOMAIN, POOL_ID, Map.of("CertificateArn", RENEWED_CERTIFICATE_ARN), 2))
                .thenReturn(domain(DOMAIN, POOL_ID, CLOUDFRONT));
        StackResource r = resource(DOMAIN, Map.of("UserPoolId", POOL_ID, "CloudFrontDistribution", CLOUDFRONT));

        provisioner.provision(r, customDomainProps(RENEWED_CERTIFICATE_ARN), ctx(DOMAIN));

        verify(cognito, never()).createUserPoolDomain(any(), any(), any(), any());
        verify(cognito, never()).deleteUserPoolDomain(any(), any());
        assertEquals(DOMAIN, r.getPhysicalId());
        assertEquals(Map.of("UserPoolId", POOL_ID, "CloudFrontDistribution", CLOUDFRONT), r.getAttributes());
    }

    @Test
    void updateWithChangedDomainReplacesTheDomain() {
        when(cognito.describeUserPoolDomain("old.example.com"))
                .thenReturn(domain("old.example.com", POOL_ID, "dold.cloudfront.net"));
        when(cognito.createUserPoolDomain(DOMAIN, POOL_ID, Map.of("CertificateArn", CERTIFICATE_ARN), 2))
                .thenReturn(domain(DOMAIN, POOL_ID, CLOUDFRONT));
        StackResource r = resource("old.example.com",
                Map.of("UserPoolId", POOL_ID, "CloudFrontDistribution", "dold.cloudfront.net"));

        provisioner.provision(r, customDomainProps(CERTIFICATE_ARN), ctx("old.example.com"));

        verify(cognito, never()).updateUserPoolDomain(any(), any(), any(), any());
        verify(cognito).deleteUserPoolDomain("old.example.com", POOL_ID);
        assertEquals(DOMAIN, r.getPhysicalId());
        assertEquals(Map.of("UserPoolId", POOL_ID, "CloudFrontDistribution", CLOUDFRONT), r.getAttributes());
    }

    @Test
    void updateWithChangedUserPoolFailsWhileTheDomainNameIsTaken() {
        // CloudFormation creates the replacement before deleting the original, and domain names are
        // unique across pools, so moving an unchanged domain to another pool fails on AWS too. The
        // original is left untouched for the rollback.
        String otherPool = "us-east-1_ZzZzZzZzZ";
        when(cognito.describeUserPoolDomain(DOMAIN)).thenReturn(domain(DOMAIN, otherPool, CLOUDFRONT));
        when(cognito.createUserPoolDomain(DOMAIN, POOL_ID, Map.of("CertificateArn", CERTIFICATE_ARN), 2))
                .thenThrow(new AwsException("InvalidParameterException",
                        "Domain " + DOMAIN + " already associated with another user pool", 400));
        StackResource r = resource(DOMAIN, Map.of("UserPoolId", otherPool, "CloudFrontDistribution", CLOUDFRONT));

        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.provision(r, customDomainProps(CERTIFICATE_ARN), ctx(DOMAIN)));

        assertEquals("InvalidParameterException", failure.getErrorCode());
        verify(cognito, never()).updateUserPoolDomain(any(), any(), any(), any());
        verify(cognito, never()).deleteUserPoolDomain(any(), any());
    }

    @Test
    void updateWhosePriorDomainIsGoneCreatesItAgain() {
        when(cognito.describeUserPoolDomain(DOMAIN))
                .thenThrow(new AwsException("ResourceNotFoundException", "Domain does not exist", 404));
        when(cognito.createUserPoolDomain(DOMAIN, POOL_ID, Map.of("CertificateArn", CERTIFICATE_ARN), 2))
                .thenReturn(domain(DOMAIN, POOL_ID, CLOUDFRONT));
        StackResource r = resource(DOMAIN, Map.of("UserPoolId", POOL_ID, "CloudFrontDistribution", CLOUDFRONT));

        provisioner.provision(r, customDomainProps(CERTIFICATE_ARN), ctx(DOMAIN));

        verify(cognito, never()).updateUserPoolDomain(any(), any(), any(), any());
        verify(cognito, never()).deleteUserPoolDomain(any(), any());
        assertEquals(DOMAIN, r.getPhysicalId());
    }

    @Test
    void replacementToleratesAPriorDomainThatIsAlreadyGone() {
        when(cognito.describeUserPoolDomain("old.example.com"))
                .thenReturn(domain("old.example.com", POOL_ID, "dold.cloudfront.net"));
        when(cognito.createUserPoolDomain(any(), any(), any(), any())).thenReturn(domain(DOMAIN, POOL_ID, CLOUDFRONT));
        doThrow(new AwsException("ResourceNotFoundException", "Domain does not exist", 404))
                .when(cognito).deleteUserPoolDomain("old.example.com", POOL_ID);
        StackResource r = resource("old.example.com", Map.of("UserPoolId", POOL_ID));

        assertDoesNotThrow(() -> provisioner.provision(r, customDomainProps(CERTIFICATE_ARN), ctx("old.example.com")));
        assertEquals(DOMAIN, r.getPhysicalId());
    }

    @Test
    void deleteUsesTheRecordedUserPoolId() {
        provisioner.delete(resource(DOMAIN, Map.of("UserPoolId", POOL_ID, "CloudFrontDistribution", CLOUDFRONT)), REGION);

        verify(cognito).deleteUserPoolDomain(DOMAIN, POOL_ID);
        verify(cognito, never()).describeUserPoolDomain(any());
    }

    @Test
    void deleteToleratesAnAlreadyDeletedDomain() {
        doThrow(new AwsException("ResourceNotFoundException", "Domain does not exist", 404))
                .when(cognito).deleteUserPoolDomain(DOMAIN, POOL_ID);

        assertDoesNotThrow(() -> provisioner.delete(resource(DOMAIN, Map.of("UserPoolId", POOL_ID)), REGION));
    }

    @Test
    void deletePropagatesOtherFailures() {
        doThrow(new AwsException("InvalidParameterException", "Domain is required", 400))
                .when(cognito).deleteUserPoolDomain(DOMAIN, POOL_ID);

        assertThrows(AwsException.class,
                () -> provisioner.delete(resource(DOMAIN, Map.of("UserPoolId", POOL_ID)), REGION));
    }

    @Test
    void deleteWithoutARecordedUserPoolIdLooksTheDomainUp() {
        when(cognito.describeUserPoolDomain(DOMAIN)).thenReturn(domain(DOMAIN, POOL_ID, CLOUDFRONT));

        provisioner.delete(resource(DOMAIN, Map.of()), REGION);

        verify(cognito).deleteUserPoolDomain(DOMAIN, POOL_ID);
    }

    @Test
    void deleteWithoutARecordedUserPoolIdOfAMissingDomainIsANoOp() {
        when(cognito.describeUserPoolDomain(DOMAIN))
                .thenThrow(new AwsException("ResourceNotFoundException", "Domain does not exist", 404));

        assertDoesNotThrow(() -> provisioner.delete(resource(DOMAIN, Map.of()), REGION));
        verify(cognito, never()).deleteUserPoolDomain(any(), any());
    }

    @Test
    void deleteByIdAloneLooksTheDomainUp() {
        when(cognito.describeUserPoolDomain(DOMAIN)).thenReturn(domain(DOMAIN, POOL_ID, CLOUDFRONT));

        provisioner.delete(TYPE, DOMAIN, REGION);

        verify(cognito).deleteUserPoolDomain(DOMAIN, POOL_ID);
    }

    @Test
    void userPoolCreateHandsTheResolvedPropertiesToTheServiceAndSetsTheSchemaAttributes() {
        when(cognito.createUserPool(any(), eq(REGION))).thenReturn(pool(POOL_ID, "my-pool"));
        when(cognito.getIssuer(POOL_ID)).thenReturn(ISSUER);
        ObjectNode props = mapper.createObjectNode().put("UserPoolName", "my-pool").put("MfaConfiguration", "OFF");
        props.putObject("Policies").putObject("PasswordPolicy").put("MinimumLength", 12);
        StackResource r = resource(USER_POOL, "Pool");

        provisioner.provision(r, props, ctx());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> request = ArgumentCaptor.forClass(Map.class);
        verify(cognito).createUserPool(request.capture(), eq(REGION));
        assertEquals("my-pool", request.getValue().get("PoolName"));
        assertEquals("OFF", request.getValue().get("MfaConfiguration"));
        assertEquals(Map.of("PasswordPolicy", Map.of("MinimumLength", 12L)), request.getValue().get("Policies"));
        assertEquals(POOL_ID, r.getPhysicalId());
        assertEquals(Set.of("Arn", "UserPoolId", "ProviderName", "ProviderURL"), r.getAttributes().keySet());
        assertEquals(POOL_ARN, r.getAttributes().get("Arn"));
        assertEquals(POOL_ID, r.getAttributes().get("UserPoolId"));
        assertEquals("cognito-idp.us-east-1.amazonaws.com/" + POOL_ID, r.getAttributes().get("ProviderName"));
        assertEquals(ISSUER, r.getAttributes().get("ProviderURL"));
    }

    @Test
    void userPoolTagsAreResolvedAsAStringMap() {
        when(cognito.createUserPool(any(), eq(REGION))).thenReturn(pool(POOL_ID, "my-pool"));
        ObjectNode props = mapper.createObjectNode().put("UserPoolName", "my-pool");
        props.putObject("UserPoolTags").put("env", "test").put("cost-center", 42);

        provisioner.provision(resource(USER_POOL, "Pool"), props, ctx());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> request = ArgumentCaptor.forClass(Map.class);
        verify(cognito).createUserPool(request.capture(), eq(REGION));
        assertEquals(Map.of("env", "test", "cost-center", "42"), request.getValue().get("UserPoolTags"));
    }

    @Test
    void userPoolTagsGivenAsAKeyValueListAreStillAccepted() {
        when(cognito.createUserPool(any(), eq(REGION))).thenReturn(pool(POOL_ID, "my-pool"));
        ObjectNode props = mapper.createObjectNode().put("UserPoolName", "my-pool");
        props.putArray("UserPoolTags").addObject().put("Key", "team").put("Value", "auth");

        provisioner.provision(resource(USER_POOL, "Pool"), props, ctx());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> request = ArgumentCaptor.forClass(Map.class);
        verify(cognito).createUserPool(request.capture(), eq(REGION));
        assertEquals(Map.of("team", "auth"), request.getValue().get("UserPoolTags"));
    }

    @Test
    void userPoolUpdateCarriesTheTags() {
        when(cognito.updateUserPool(any(), eq(REGION))).thenReturn(pool(POOL_ID, "my-pool"));
        ObjectNode props = mapper.createObjectNode().put("UserPoolName", "my-pool");
        props.putObject("UserPoolTags").put("env", "prod");
        StackResource r = resource(USER_POOL, "Pool");
        r.setPhysicalId(POOL_ID);

        provisioner.provision(r, props, ctx(POOL_ID));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> request = ArgumentCaptor.forClass(Map.class);
        verify(cognito).updateUserPool(request.capture(), eq(REGION));
        assertEquals(Map.of("env", "prod"), request.getValue().get("UserPoolTags"));
    }

    @Test
    void userPoolWithoutANameGetsAGeneratedOne() {
        when(cognito.createUserPool(any(), eq(REGION))).thenAnswer(inv ->
                pool(POOL_ID, (String) inv.<Map<String, Object>>getArgument(0).get("PoolName")));
        StackResource r = resource(USER_POOL, "Pool");

        provisioner.provision(r, mapper.createObjectNode(), ctx());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> request = ArgumentCaptor.forClass(Map.class);
        verify(cognito).createUserPool(request.capture(), eq(REGION));
        String poolName = (String) request.getValue().get("PoolName");
        assertTrue(poolName.startsWith("my-stack-Pool-"), poolName);
    }

    @Test
    void userPoolUpdateCarriesThePriorPoolId() {
        when(cognito.updateUserPool(any(), eq(REGION))).thenReturn(pool(POOL_ID, "my-pool"));
        StackResource r = resource(USER_POOL, "Pool");
        r.setPhysicalId(POOL_ID);

        provisioner.provision(r, mapper.createObjectNode().put("UserPoolName", "my-pool"), ctx(POOL_ID));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> request = ArgumentCaptor.forClass(Map.class);
        verify(cognito).updateUserPool(request.capture(), eq(REGION));
        assertEquals(POOL_ID, request.getValue().get("UserPoolId"));
        verify(cognito, never()).createUserPool(any(), any());
        assertEquals(POOL_ID, r.getPhysicalId());
    }

    @Test
    void userPoolUpdateWithAnExplicitNameCarriesIt() {
        when(cognito.updateUserPool(any(), eq(REGION))).thenReturn(pool(POOL_ID, "renamed"));
        StackResource r = resource(USER_POOL, "Pool");
        r.setPhysicalId(POOL_ID);

        provisioner.provision(r, mapper.createObjectNode().put("UserPoolName", "renamed"), ctx(POOL_ID));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> request = ArgumentCaptor.forClass(Map.class);
        verify(cognito).updateUserPool(request.capture(), eq(REGION));
        assertEquals("renamed", request.getValue().get("PoolName"));
    }

    @Test
    void userPoolUpdateWithoutANameKeepsTheCurrentOne() {
        when(cognito.updateUserPool(any(), eq(REGION))).thenReturn(pool(POOL_ID, "my-stack-Pool-abc123"));
        StackResource r = resource(USER_POOL, "Pool");
        r.setPhysicalId(POOL_ID);

        provisioner.provision(r, mapper.createObjectNode().put("MfaConfiguration", "OFF"), ctx(POOL_ID));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> request = ArgumentCaptor.forClass(Map.class);
        verify(cognito).updateUserPool(request.capture(), eq(REGION));
        assertFalse(request.getValue().containsKey("PoolName"), "no PoolName means keep the current name");
    }

    @Test
    void userPoolClientCreatePassesEveryPropertyPositionally() {
        stubClientCreate(client("web", "s3cr3t"));
        ObjectNode props = mapper.createObjectNode()
                .put("UserPoolId", POOL_ID)
                .put("ClientName", "web")
                .put("GenerateSecret", true)
                .put("AccessTokenValidity", 60)
                .put("PreventUserExistenceErrors", "ENABLED");
        props.putArray("ExplicitAuthFlows").add("ALLOW_USER_SRP_AUTH").add("ALLOW_REFRESH_TOKEN_AUTH");
        props.putArray("CallbackURLs").add("https://app.example.com/cb");
        props.putObject("TokenValidityUnits").put("AccessToken", "minutes");
        StackResource r = resource(USER_POOL_CLIENT, "Client");

        provisioner.provision(r, props, ctx());

        verify(cognito).createUserPoolClient(eq(POOL_ID), eq("web"), eq(true), eq(false),
                eq(List.of()), eq(List.of()), isNull(), eq(List.of("https://app.example.com/cb")),
                isNull(), eq(List.of("ALLOW_USER_SRP_AUTH", "ALLOW_REFRESH_TOKEN_AUTH")), eq(60), isNull(),
                eq(List.of()), eq("ENABLED"), eq(List.of()), isNull(),
                eq(List.of()), eq(Map.of("AccessToken", "minutes")), eq(List.of()),
                isNull(), isNull());
        assertEquals(CLIENT_ID, r.getPhysicalId());
        assertEquals(Set.of("ClientId", "Name", "ClientSecret"), r.getAttributes().keySet());
        assertEquals(CLIENT_ID, r.getAttributes().get("ClientId"));
        assertEquals("web", r.getAttributes().get("Name"));
        assertEquals("s3cr3t", r.getAttributes().get("ClientSecret"));
    }

    @Test
    void userPoolClientWithoutASecretHasNoClientSecretAttribute() {
        stubClientCreate(client("web", null));
        StackResource r = resource(USER_POOL_CLIENT, "Client");

        provisioner.provision(r, mapper.createObjectNode().put("UserPoolId", POOL_ID).put("ClientName", "web"), ctx());

        assertEquals(Set.of("ClientId", "Name"), r.getAttributes().keySet());
    }

    @Test
    void userPoolClientWithoutANameGetsAGeneratedOne() {
        stubClientCreate(client("generated", null));
        StackResource r = resource(USER_POOL_CLIENT, "Client");

        provisioner.provision(r, mapper.createObjectNode().put("UserPoolId", POOL_ID), ctx());

        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        verify(cognito).createUserPoolClient(eq(POOL_ID), name.capture(), anyBoolean(), anyBoolean(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        assertTrue(name.getValue().startsWith("my-stack-Client-"), name.getValue());
    }

    @Test
    void userPoolClientUpdateUpdatesThePriorClientInPlace() {
        when(cognito.describeUserPoolClient(CLIENT_ID)).thenReturn(client("old-name", null));
        stubClientUpdate(client("web", null));
        StackResource r = resource(USER_POOL_CLIENT, "Client");
        r.setPhysicalId(CLIENT_ID);
        ObjectNode props = mapper.createObjectNode().put("UserPoolId", POOL_ID).put("ClientName", "web")
                .put("EnableTokenRevocation", true);

        provisioner.provision(r, props, ctx(CLIENT_ID));

        verify(cognito).updateUserPoolClient(eq(POOL_ID), eq(CLIENT_ID), eq("web"), eq(false),
                eq(List.of()), eq(List.of()), isNull(), eq(List.of()), isNull(), eq(List.of()), isNull(), isNull(),
                eq(List.of()), isNull(), eq(List.of()), isNull(), eq(List.of()), isNull(), eq(List.of()),
                isNull(), eq(Boolean.TRUE));
        verifyNoClientCreate();
        assertEquals(CLIENT_ID, r.getPhysicalId());
        assertFalse(provisioner.hasReplacementUpdate(r));
    }

    @Test
    void movingAUserPoolClientToAnotherPoolReplacesIt() {
        when(cognito.describeUserPoolClient(CLIENT_ID)).thenReturn(client(CLIENT_ID, POOL_ID, "web", null));
        stubClientCreate(client("replacement-client", "us-east-1_OtherPool", "web", null));
        StackResource r = resource(USER_POOL_CLIENT, "Client");
        r.setPhysicalId(CLIENT_ID);
        r.getAttributes().putAll(Map.of("ClientId", CLIENT_ID, "Name", "web"));
        ObjectNode props = mapper.createObjectNode().put("UserPoolId", "us-east-1_OtherPool").put("ClientName", "web");

        provisioner.provision(r, props, ctx(CLIENT_ID));

        verify(cognito).createUserPoolClient(eq("us-east-1_OtherPool"), eq("web"), eq(false), anyBoolean(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verifyNoClientUpdate();
        assertEquals("replacement-client", r.getPhysicalId());
        assertTrue(provisioner.hasReplacementUpdate(r));
        assertEquals(CLIENT_ID, provisioner.updateCleanupPhysicalId(r));
    }

    @Test
    void changingGenerateSecretReplacesTheUserPoolClient() {
        when(cognito.describeUserPoolClient(CLIENT_ID)).thenReturn(client(CLIENT_ID, POOL_ID, "web", null));
        stubClientCreate(client("replacement-client", POOL_ID, "web", "s3cr3t"));
        StackResource r = resource(USER_POOL_CLIENT, "Client");
        r.setPhysicalId(CLIENT_ID);
        ObjectNode props = mapper.createObjectNode().put("UserPoolId", POOL_ID).put("ClientName", "web")
                .put("GenerateSecret", true);

        provisioner.provision(r, props, ctx(CLIENT_ID));

        verify(cognito).createUserPoolClient(eq(POOL_ID), eq("web"), eq(true), anyBoolean(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verifyNoClientUpdate();
        assertEquals("replacement-client", r.getPhysicalId());
        assertEquals("s3cr3t", r.getAttributes().get("ClientSecret"));
        assertEquals(CLIENT_ID, provisioner.updateCleanupPhysicalId(r));
    }

    @Test
    void replacingASecretBearingClientWithASecretlessOneDropsTheStaleSecretAndRollbackRestoresIt() {
        when(cognito.describeUserPoolClient(CLIENT_ID)).thenReturn(client(CLIENT_ID, POOL_ID, "web", "old-secret"));
        stubClientCreate(client("replacement-client", POOL_ID, "web", null));
        StackResource r = resource(USER_POOL_CLIENT, "Client");
        r.setPhysicalId(CLIENT_ID);
        r.getAttributes().putAll(Map.of("ClientId", CLIENT_ID, "Name", "web", "ClientSecret", "old-secret"));
        ObjectNode props = mapper.createObjectNode().put("UserPoolId", POOL_ID).put("ClientName", "web")
                .put("GenerateSecret", false);

        provisioner.provision(r, props, ctx(CLIENT_ID));

        assertEquals("replacement-client", r.getPhysicalId());
        assertFalse(r.getAttributes().containsKey("ClientSecret"),
                "the displaced client's secret must not survive the replacement");
        assertEquals("replacement-client", r.getAttributes().get("ClientId"));

        assertTrue(provisioner.rollbackUpdate(r));
        assertEquals(CLIENT_ID, r.getPhysicalId());
        assertEquals("old-secret", r.getAttributes().get("ClientSecret"));
    }

    @Test
    void aReplacementThatWouldReuseThePriorClientIdIsRefusedBeforeAnythingChanges() {
        when(cognito.describeUserPoolClient("web")).thenReturn(client("web", POOL_ID, "web", null));
        when(cognito.deterministicClientIdFor(POOL_ID, "web")).thenReturn("web");
        StackResource r = resource(USER_POOL_CLIENT, "Client");
        r.setPhysicalId("web");
        ObjectNode props = mapper.createObjectNode().put("UserPoolId", POOL_ID).put("ClientName", "web")
                .put("GenerateSecret", true);

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx("web")));

        assertEquals("ValidationError", e.getErrorCode());
        verifyNoClientCreate();
        verifyNoClientUpdate();
        assertEquals("web", r.getPhysicalId());
        assertFalse(provisioner.hasReplacementUpdate(r));
    }

    @Test
    void aReplacementWhoseDerivedIdBelongsToAnotherClientIsRefused() {
        when(cognito.describeUserPoolClient("web")).thenReturn(client("web", POOL_ID, "web", null));
        when(cognito.deterministicClientIdFor("us-east-1_OtherPool", "web"))
                .thenReturn("web");
        when(cognito.describeUserPoolClient("web")).thenReturn(client("web", POOL_ID, "web", null));
        StackResource r = resource(USER_POOL_CLIENT, "Client");
        r.setPhysicalId("web");
        // Same name in another pool with the same override: the derived id is the prior's, which
        // stands for any existing client the store would overwrite.
        ObjectNode props = mapper.createObjectNode().put("UserPoolId", "us-east-1_OtherPool").put("ClientName", "web");

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx("web")));

        assertEquals("ValidationError", e.getErrorCode());
        verifyNoClientCreate();
    }

    @Test
    void aFreshClientWhoseDerivedIdIsTakenByAnotherClientIsRefused() {
        when(cognito.deterministicClientIdFor(POOL_ID, "taken")).thenReturn("taken");
        when(cognito.describeUserPoolClient("taken")).thenReturn(client("taken", "us-east-1_OtherPool", "taken", null));
        StackResource r = resource(USER_POOL_CLIENT, "Client");

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r,
                mapper.createObjectNode().put("UserPoolId", POOL_ID).put("ClientName", "taken"), ctx()));

        assertEquals("ValidationError", e.getErrorCode());
        verifyNoClientCreate();
        assertEquals(null, r.getPhysicalId());
    }

    @Test
    void aDerivedIdHeldByAClientOfADeletedPoolIsTakenOver() {
        when(cognito.deterministicClientIdFor(POOL_ID, "web")).thenReturn("web");
        when(cognito.describeUserPoolClient("web")).thenReturn(client("web", "us-east-1_DeletedPool", "web", null));
        when(cognito.describeUserPool("us-east-1_DeletedPool"))
                .thenThrow(new AwsException("ResourceNotFoundException", "User pool does not exist", 400));
        stubClientCreate(client("web", POOL_ID, "web", null));
        StackResource r = resource(USER_POOL_CLIENT, "Client");

        provisioner.provision(r, mapper.createObjectNode().put("UserPoolId", POOL_ID).put("ClientName", "web"), ctx());

        assertEquals("web", r.getPhysicalId());
    }

    @Test
    void concurrentCreatesOfTheSameDerivedIdLetExactlyOneThrough() throws Exception {
        when(cognito.deterministicClientIdFor(POOL_ID, "web")).thenReturn("web");
        AtomicBoolean created = new AtomicBoolean();
        when(cognito.describeUserPoolClient("web")).thenAnswer(inv -> {
            if (created.get()) {
                return client("web", POOL_ID, "web", null);
            }
            throw new AwsException("ResourceNotFoundException", "User pool client not found", 400);
        });
        AtomicInteger creates = new AtomicInteger();
        when(cognito.createUserPoolClient(any(), any(), anyBoolean(), anyBoolean(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    creates.incrementAndGet();
                    Thread.sleep(50);
                    created.set(true);
                    return client("web", POOL_ID, "web", null);
                });
        CountDownLatch ready = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Throwable>> outcomes = new java.util.ArrayList<>();
            for (int i = 0; i < 2; i++) {
                outcomes.add(pool.submit(() -> {
                    ready.countDown();
                    ready.await(5, TimeUnit.SECONDS);
                    try {
                        provisioner.provision(resource(USER_POOL_CLIENT, "Client"),
                                mapper.createObjectNode().put("UserPoolId", POOL_ID).put("ClientName", "web"), ctx());
                        return null;
                    } catch (Throwable t) {
                        return t;
                    }
                }));
            }
            long refused = outcomes.stream().map(f -> {
                try {
                    return f.get(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            }).filter(t -> t instanceof AwsException a && "ValidationError".equals(a.getErrorCode())).count();

            assertEquals(1, creates.get(), "exactly one create");
            assertEquals(1, refused, "the other provision is refused, not silently overwriting");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aFreshClientWhoseDerivedIdIsFreeIsCreated() {
        when(cognito.deterministicClientIdFor(POOL_ID, "web")).thenReturn("web");
        when(cognito.describeUserPoolClient("web"))
                .thenThrow(new AwsException("ResourceNotFoundException", "User pool client not found", 400));
        stubClientCreate(client("web", POOL_ID, "web", null));
        StackResource r = resource(USER_POOL_CLIENT, "Client");

        provisioner.provision(r, mapper.createObjectNode().put("UserPoolId", POOL_ID).put("ClientName", "web"), ctx());

        assertEquals("web", r.getPhysicalId());
    }

    @Test
    void aReplacementWhoseDerivedIdDiffersProceeds() {
        when(cognito.describeUserPoolClient("web")).thenReturn(client("web", POOL_ID, "web", null));
        when(cognito.deterministicClientIdFor(POOL_ID, "web-v2")).thenReturn("web-v2");
        when(cognito.describeUserPoolClient("web-v2"))
                .thenThrow(new AwsException("ResourceNotFoundException", "User pool client not found", 400));
        stubClientCreate(client("web-v2", POOL_ID, "web-v2", "s3cr3t"));
        StackResource r = resource(USER_POOL_CLIENT, "Client");
        r.setPhysicalId("web");
        ObjectNode props = mapper.createObjectNode().put("UserPoolId", POOL_ID).put("ClientName", "web-v2")
                .put("GenerateSecret", true);

        provisioner.provision(r, props, ctx("web"));

        assertEquals("web-v2", r.getPhysicalId());
        assertEquals("web", provisioner.updateCleanupPhysicalId(r));
    }

    @Test
    void replacedUserPoolClientIsDeletedWhenTheUpdateCompletes() {
        when(cognito.describeUserPoolClient(CLIENT_ID)).thenReturn(client(CLIENT_ID, POOL_ID, "web", null));
        stubClientCreate(client("replacement-client", "us-east-1_OtherPool", "web", null));
        StackResource r = resource(USER_POOL_CLIENT, "Client");
        r.setPhysicalId(CLIENT_ID);
        provisioner.provision(r, mapper.createObjectNode().put("UserPoolId", "us-east-1_OtherPool"), ctx(CLIENT_ID));

        provisioner.completeUpdate(r);

        verify(cognito).deleteUserPoolClient(CLIENT_ID);
        assertFalse(provisioner.hasReplacementUpdate(r));
    }

    @Test
    void rollingBackAReplacedUserPoolClientRestoresThePriorOneAndDeletesTheReplacement() {
        when(cognito.describeUserPoolClient(CLIENT_ID)).thenReturn(client(CLIENT_ID, POOL_ID, "web", null));
        stubClientCreate(client("replacement-client", "us-east-1_OtherPool", "web", null));
        StackResource r = resource(USER_POOL_CLIENT, "Client");
        r.setPhysicalId(CLIENT_ID);
        r.getAttributes().putAll(Map.of("ClientId", CLIENT_ID, "Name", "web"));
        provisioner.provision(r, mapper.createObjectNode().put("UserPoolId", "us-east-1_OtherPool"), ctx(CLIENT_ID));

        assertTrue(provisioner.rollbackUpdate(r));

        verify(cognito).deleteUserPoolClient("replacement-client");
        verify(cognito, never()).deleteUserPoolClient(CLIENT_ID);
        assertEquals(CLIENT_ID, r.getPhysicalId());
        assertEquals(CLIENT_ID, r.getAttributes().get("ClientId"));
        assertFalse(provisioner.hasReplacementUpdate(r));
    }

    @Test
    void anInPlaceUserPoolClientUpdateCannotBeRolledBack() {
        when(cognito.describeUserPoolClient(CLIENT_ID)).thenReturn(client("web", null));
        stubClientUpdate(client("web", null));
        StackResource r = resource(USER_POOL_CLIENT, "Client");
        r.setPhysicalId(CLIENT_ID);
        provisioner.provision(r, mapper.createObjectNode().put("UserPoolId", POOL_ID), ctx(CLIENT_ID));

        assertFalse(provisioner.rollbackUpdate(r));
    }

    @Test
    void aPriorUserPoolClientThatIsGoneIsCreatedAnew() {
        when(cognito.describeUserPoolClient(CLIENT_ID))
                .thenThrow(new AwsException("ResourceNotFoundException", "User pool client not found", 400));
        stubClientCreate(client("web", null));
        StackResource r = resource(USER_POOL_CLIENT, "Client");
        r.setPhysicalId(CLIENT_ID);

        assertDoesNotThrow(() -> provisioner.provision(r,
                mapper.createObjectNode().put("UserPoolId", POOL_ID).put("ClientName", "web"), ctx(CLIENT_ID)));

        verifyNoClientUpdate();
        assertEquals(CLIENT_ID, r.getPhysicalId());
    }

    @Test
    void aFailingPriorUserPoolClientLookupPropagates() {
        when(cognito.describeUserPoolClient(CLIENT_ID))
                .thenThrow(new AwsException("InternalErrorException", "storage unavailable", 500));
        StackResource r = resource(USER_POOL_CLIENT, "Client");
        r.setPhysicalId(CLIENT_ID);

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r,
                mapper.createObjectNode().put("UserPoolId", POOL_ID), ctx(CLIENT_ID)));
        assertEquals("InternalErrorException", e.getErrorCode());
    }

    @Test
    void userPoolClientWithoutAUserPoolIdIsRejected() {
        StackResource r = resource(USER_POOL_CLIENT, "Client");

        AwsException e = assertThrows(AwsException.class,
                () -> provisioner.provision(r, mapper.createObjectNode().put("ClientName", "web"), ctx()));
        assertEquals("ValidationError", e.getErrorCode());
        verifyNoClientCreate();
    }

    @Test
    void nonIntegerTokenValidityIsRejectedInsteadOfDropped() {
        for (String property : List.of("AccessTokenValidity", "IdTokenValidity", "RefreshTokenValidity")) {
            StackResource r = resource(USER_POOL_CLIENT, "Client");
            ObjectNode props = mapper.createObjectNode().put("UserPoolId", POOL_ID).put(property, "sixty");

            AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx()), property);
            assertEquals("ValidationError", e.getErrorCode());
            assertEquals("Value of property " + property + " must be an integer.", e.getMessage());
        }
        verifyNoClientCreate();
    }

    @Test
    void unknownTypeIsRejected() {
        StackResource r = resource("AWS::Cognito::IdentityPool", "Identity");

        assertThrows(IllegalStateException.class, () -> provisioner.provision(r, mapper.createObjectNode(), ctx()));
    }

    @Test
    void deleteUserPoolToleratesAMissingPoolAndPropagatesADomainRefusal() {
        doThrow(new AwsException("ResourceNotFoundException", "User pool does not exist", 400))
                .when(cognito).deleteUserPool("us-east-1_gone");
        doThrow(new AwsException("InvalidParameterException", "User pool cannot be deleted.", 400))
                .when(cognito).deleteUserPool(POOL_ID);

        assertDoesNotThrow(() -> provisioner.delete(USER_POOL, "us-east-1_gone", REGION));
        AwsException refused = assertThrows(AwsException.class, () -> provisioner.delete(USER_POOL, POOL_ID, REGION));
        assertEquals("InvalidParameterException", refused.getErrorCode());
    }

    @Test
    void deleteUserPoolClientToleratesAnAlreadyDeletedClient() {
        doThrow(new AwsException("ResourceNotFoundException", "User pool client not found", 400))
                .when(cognito).deleteUserPoolClient(CLIENT_ID);

        assertDoesNotThrow(() -> provisioner.delete(USER_POOL_CLIENT, CLIENT_ID, REGION));
        verify(cognito).deleteUserPoolClient(CLIENT_ID);
    }

    @Test
    void deletingAUserPoolThroughTheResourceOverloadDeletesThePoolNotADomain() {
        StackResource r = resource(USER_POOL, "Pool");
        r.setPhysicalId(POOL_ID);
        r.getAttributes().put("UserPoolId", POOL_ID);

        provisioner.delete(r, REGION);

        verify(cognito).deleteUserPool(POOL_ID);
        verify(cognito, never()).deleteUserPoolDomain(any(), any());
    }

    @Test
    void deleteWithoutAPhysicalIdIsANoOp() {
        assertDoesNotThrow(() -> provisioner.delete(USER_POOL, null, REGION));
        assertDoesNotThrow(() -> provisioner.delete(USER_POOL_CLIENT, "", REGION));
        verify(cognito, never()).deleteUserPool(any());
        verify(cognito, never()).deleteUserPoolClient(any());
    }
}
