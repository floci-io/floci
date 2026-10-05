package com.floci.test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.PartitionMetadata;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.Output;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.cloudformation.model.StackResource;
import software.amazon.awssdk.services.iot.IotClient;
import software.amazon.awssdk.services.iot.model.AuthorizerDescription;
import software.amazon.awssdk.services.iot.model.AuthorizerStatus;
import software.amazon.awssdk.services.iot.model.ResourceNotFoundException;
import software.amazon.awssdk.services.iot.model.Tag;
import software.amazon.awssdk.services.sts.StsClient;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CloudFormation AWS::IoT::Authorizer")
class CloudFormationIotAuthorizerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long TIMEOUT_NANOS = 300_000_000_000L;

    private static CloudFormationClient cfn;
    private static IotClient iot;
    private static String partition;
    private static String region;
    private static String account;
    private static String rsaKey;

    private String prefix;
    private String stackName;
    private String stackId;
    private final Set<String> authorizers = new LinkedHashSet<>();

    @BeforeAll
    static void clients() throws GeneralSecurityException {
        cfn = TestFixtures.cloudFormationClient();
        iot = TestFixtures.iotClient();
        Region clientRegion = iot.serviceClientConfiguration().region();
        region = clientRegion.id();
        partition = PartitionMetadata.of(clientRegion).id();
        try (StsClient sts = TestFixtures.stsClient()) {
            account = sts.getCallerIdentity().account();
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        byte[] der = generator.generateKeyPair().getPublic().getEncoded();
        rsaKey = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
                + "\n-----END PUBLIC KEY-----";
    }

    @BeforeEach
    void setup() {
        prefix = "compat-cfn-auth-" + UUID.randomUUID().toString().substring(0, 8);
        stackName = prefix;
    }

    @AfterEach
    void cleanup() throws InterruptedException {
        collectStackAuthorizers();
        try {
            deleteStack();
        } finally {
            for (String name : authorizers) {
                removeAuthorizer(name);
            }
            authorizers.clear();
            deleteStack();
        }
    }

    @AfterAll
    static void closeClients() {
        cfn.close();
        iot.close();
    }

    @Test
    @DisplayName("Create a named ACTIVE authorizer, read it back, then delete the stack while it is ACTIVE")
    void createsNamedActiveAuthorizerAndDeletesItWithTheStackWhileActive() throws InterruptedException {
        String name = prefix + "-auth";
        String function = functionArn("fn");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("AuthorizerName", name);
        properties.put("AuthorizerFunctionArn", function);
        properties.put("SigningDisabled", true);
        properties.put("Status", "ACTIVE");
        properties.put("Tags", List.of(Map.of("Key", "team", "Value", "a")));
        authorizers.add(name);

        createStack(template(properties));

        assertThat(output("AuthRef")).as("Ref is the authorizer name").isEqualTo(name);
        assertThat(output("AuthArn")).as("GetAtt Arn is the authorizer ARN").isEqualTo(authorizerArn(name));
        AuthorizerDescription description = describe(name);
        assertThat(description.authorizerArn()).isEqualTo(authorizerArn(name));
        assertThat(description.status()).isEqualTo(AuthorizerStatus.ACTIVE);
        assertThat(description.signingDisabled()).isTrue();
        assertThat(description.enableCachingForHttp()).isFalse();
        assertThat(description.authorizerFunctionArn()).isEqualTo(function);
        assertThat(tags(name)).containsOnly(Map.entry("team", "a"));

        deleteStack();
        assertNotFound(name);
    }

    @Test
    @DisplayName("Status, caching, tags and function ARN update the named authorizer in place")
    void inPlaceUpdatesKeepTheNamedAuthorizer() throws InterruptedException {
        String name = prefix + "-auth";
        String firstFunction = functionArn("fn1");
        String secondFunction = functionArn("fn2");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("AuthorizerName", name);
        properties.put("AuthorizerFunctionArn", firstFunction);
        properties.put("SigningDisabled", true);
        properties.put("Status", "ACTIVE");
        properties.put("Tags", List.of(Map.of("Key", "team", "Value", "a"), Map.of("Key", "stage", "Value", "x")));
        authorizers.add(name);
        createStack(template(properties));
        String arn = output("AuthArn");
        assertThat(describe(name).status()).isEqualTo(AuthorizerStatus.ACTIVE);

        properties.put("Status", "INACTIVE");
        updateStack(template(properties));
        assertIdentity(name, arn);
        AuthorizerDescription inactive = describe(name);
        assertThat(inactive.status()).isEqualTo(AuthorizerStatus.INACTIVE);
        assertThat(inactive.enableCachingForHttp()).isFalse();
        assertThat(tags(name)).containsOnly(Map.entry("team", "a"), Map.entry("stage", "x"));

        properties.put("EnableCachingForHttp", true);
        properties.put("Tags", List.of(Map.of("Key", "team", "Value", "b"), Map.of("Key", "env", "Value", "dev")));
        updateStack(template(properties));
        assertIdentity(name, arn);
        AuthorizerDescription caching = describe(name);
        assertThat(caching.enableCachingForHttp()).isTrue();
        assertThat(caching.status()).isEqualTo(AuthorizerStatus.INACTIVE);
        assertThat(tags(name)).as("a tag removed from the template is untagged")
                .containsOnly(Map.entry("team", "b"), Map.entry("env", "dev"));

        properties.remove("Status");
        properties.remove("EnableCachingForHttp");
        updateStack(template(properties));
        assertIdentity(name, arn);
        AuthorizerDescription unspecified = describe(name);
        assertThat(unspecified.status()).as("removing Status from the template keeps the last value")
                .isEqualTo(AuthorizerStatus.INACTIVE);
        assertThat(unspecified.enableCachingForHttp())
                .as("removing EnableCachingForHttp from the template keeps the last value")
                .isTrue();

        properties.put("AuthorizerFunctionArn", secondFunction);
        updateStack(template(properties));
        assertIdentity(name, arn);
        AuthorizerDescription moved = describe(name);
        assertThat(moved.authorizerFunctionArn()).isEqualTo(secondFunction);
        assertThat(moved.status()).isEqualTo(AuthorizerStatus.INACTIVE);
        assertThat(moved.enableCachingForHttp()).isTrue();
        assertThat(moved.signingDisabled()).isTrue();
    }

    @Test
    @DisplayName("Turning signing on replaces a generated authorizer and deletes the old one")
    void changingSigningDisabledReplacesAGeneratedAuthorizer() throws InterruptedException {
        String function = functionArn("fn");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("AuthorizerFunctionArn", function);
        properties.put("SigningDisabled", true);
        createStack(template(properties));

        String original = output("AuthRef");
        authorizers.add(original);
        assertGeneratedName(original);
        assertThat(output("AuthArn")).isEqualTo(authorizerArn(original));
        AuthorizerDescription before = describe(original);
        assertThat(before.signingDisabled()).isTrue();
        assertThat(before.status()).as("no Status in the template creates an INACTIVE authorizer")
                .isEqualTo(AuthorizerStatus.INACTIVE);

        properties.put("SigningDisabled", false);
        properties.put("TokenKeyName", "tok");
        properties.put("TokenSigningPublicKeys", Map.of("k1", rsaKey));
        updateStack(template(properties));

        String replacement = output("AuthRef");
        authorizers.add(replacement);
        assertGeneratedName(replacement);
        assertThat(replacement).as("SigningDisabled is create-only, so the authorizer is replaced")
                .isNotEqualTo(original);
        assertThat(output("AuthArn")).isEqualTo(authorizerArn(replacement));
        assertNotFound(original);
        AuthorizerDescription after = describe(replacement);
        assertThat(after.signingDisabled()).isFalse();
        assertThat(after.tokenKeyName()).isEqualTo("tok");
        assertThat(after.tokenSigningPublicKeys()).containsExactly(Map.entry("k1", rsaKey));
        assertThat(after.authorizerFunctionArn()).isEqualTo(function);
    }

    @Test
    @DisplayName("Deleting the stack succeeds after the authorizer was deleted out of band")
    void deletingTheStackAfterAnOutOfBandDeleteCompletes() throws InterruptedException {
        String name = prefix + "-auth";
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("AuthorizerName", name);
        properties.put("AuthorizerFunctionArn", functionArn("fn"));
        properties.put("SigningDisabled", true);
        properties.put("Status", "ACTIVE");
        authorizers.add(name);
        createStack(template(properties));
        assertThat(describe(name).status()).isEqualTo(AuthorizerStatus.ACTIVE);

        iot.updateAuthorizer(r -> r.authorizerName(name).status(AuthorizerStatus.INACTIVE));
        iot.deleteAuthorizer(r -> r.authorizerName(name));
        assertNotFound(name);

        deleteStack();
        assertNotFound(name);
    }

    private static String template(Map<String, Object> properties) {
        Map<String, Object> template = Map.of(
                "Resources", Map.of("Auth", Map.of("Type", "AWS::IoT::Authorizer", "Properties", properties)),
                "Outputs", Map.of(
                        "AuthRef", Map.of("Value", Map.of("Ref", "Auth")),
                        "AuthArn", Map.of("Value", Map.of("Fn::GetAtt", List.of("Auth", "Arn")))));
        try {
            return MAPPER.writeValueAsString(template);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to render the template", e);
        }
    }

    private String functionArn(String suffix) {
        return "arn:" + partition + ":lambda:" + region + ":" + account + ":function:" + prefix + "-" + suffix;
    }

    private String authorizerArn(String name) {
        return "arn:" + partition + ":iot:" + region + ":" + account + ":authorizer/" + name;
    }

    private AuthorizerDescription describe(String name) {
        return iot.describeAuthorizer(r -> r.authorizerName(name)).authorizerDescription();
    }

    private Map<String, String> tags(String name) {
        return iot.listTagsForResource(r -> r.resourceArn(authorizerArn(name))).tags().stream()
                .collect(Collectors.toMap(Tag::key, Tag::value, (a, b) -> b, LinkedHashMap::new));
    }

    private void assertNotFound(String name) {
        assertThatThrownBy(() -> iot.describeAuthorizer(r -> r.authorizerName(name)))
                .isInstanceOfSatisfying(ResourceNotFoundException.class, e -> {
                    assertThat(e.statusCode()).isEqualTo(404);
                    assertThat(e.awsErrorDetails().errorMessage()).isEqualTo("Authorizer " + name + " not found");
                });
    }

    private void assertIdentity(String name, String arn) {
        assertThat(output("AuthRef")).as("the update keeps the authorizer name").isEqualTo(name);
        assertThat(output("AuthArn")).isEqualTo(arn);
    }

    private static void assertGeneratedName(String name) {
        assertThat(name).as("CloudFormation names an unnamed authorizer <LogicalId>_<12 alphanumerics>")
                .matches("Auth_[A-Za-z0-9]{12}");
    }

    private void createStack(String template) throws InterruptedException {
        stackId = cfn.createStack(r -> r.stackName(stackName).templateBody(template)).stackId();
        awaitStatus("CREATE_COMPLETE");
    }

    private void updateStack(String template) throws InterruptedException {
        cfn.updateStack(r -> r.stackName(stackName).templateBody(template));
        awaitStatus("UPDATE_COMPLETE");
    }

    private String output(String key) {
        return cfn.describeStacks(r -> r.stackName(stackName)).stacks().get(0).outputs().stream()
                .filter(o -> key.equals(o.outputKey())).map(Output::outputValue)
                .findFirst().orElseThrow();
    }

    private void collectStackAuthorizers() {
        if (stackId == null) {
            return;
        }
        try {
            cfn.describeStackResources(r -> r.stackName(stackId)).stackResources().stream()
                    .filter(resource -> "AWS::IoT::Authorizer".equals(resource.resourceType()))
                    .map(StackResource::physicalResourceId)
                    .filter(id -> id != null && !id.isEmpty())
                    .forEach(authorizers::add);
        } catch (CloudFormationException e) {
            if (!e.getMessage().contains("does not exist")) {
                throw e;
            }
        }
    }

    private void removeAuthorizer(String name) {
        try {
            if (describe(name).status() == AuthorizerStatus.ACTIVE) {
                iot.updateAuthorizer(r -> r.authorizerName(name).status(AuthorizerStatus.INACTIVE));
            }
            iot.deleteAuthorizer(r -> r.authorizerName(name));
        } catch (ResourceNotFoundException expected) {
            // The stack deletion, a replacement or the test itself already removed it.
        }
    }

    private void awaitStatus(String expected) throws InterruptedException {
        await(() -> {
            Stack stack = cfn.describeStacks(r -> r.stackName(stackName)).stacks().get(0);
            String status = stack.stackStatusAsString();
            if (status.endsWith("_FAILED") || status.contains("ROLLBACK")) {
                throw new AssertionError(stackName + " reached " + status + ": " + stack.stackStatusReason());
            }
            return expected.equals(status);
        }, expected);
    }

    private void deleteStack() throws InterruptedException {
        if (stackId == null) {
            return;
        }
        cfn.deleteStack(r -> r.stackName(stackId));
        await(() -> {
            try {
                List<Stack> stacks = cfn.describeStacks(r -> r.stackName(stackId)).stacks();
                if (stacks.isEmpty()) {
                    return true;
                }
                Stack stack = stacks.get(0);
                if ("DELETE_FAILED".equals(stack.stackStatusAsString())) {
                    throw new AssertionError(stackName + " deletion failed: " + stack.stackStatusReason());
                }
                return "DELETE_COMPLETE".equals(stack.stackStatusAsString());
            } catch (CloudFormationException e) {
                if ("ValidationError".equals(e.awsErrorDetails().errorCode())
                        && e.getMessage().contains("does not exist")) {
                    return true;
                }
                throw e;
            }
        }, "stack deletion");
        stackId = null;
    }

    private void await(BooleanSupplier condition, String expected) throws InterruptedException {
        long pollMillis = TestFixtures.isRealAws() ? 2_000 : 100;
        long deadline = System.nanoTime() + TIMEOUT_NANOS;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(pollMillis);
        }
        throw new AssertionError(stackName + " timed out waiting for " + expected);
    }
}
