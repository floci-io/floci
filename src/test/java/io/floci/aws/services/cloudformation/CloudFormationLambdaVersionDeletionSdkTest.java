package io.floci.aws.services.cloudformation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.SdkPojo;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.DescribeStackEventsResponse;
import software.amazon.awssdk.services.cloudformation.model.StackEvent;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.IamException;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.FunctionConfiguration;
import software.amazon.awssdk.services.lambda.model.LambdaException;
import software.amazon.awssdk.services.lambda.model.ListVersionsByFunctionResponse;
import software.amazon.awssdk.services.lambda.model.Runtime;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CFN version deletion reaches native qualified deletion while preserving unrelated functions and versions. */
@QuarkusTest
@Timeout(120)
class CloudFormationLambdaVersionDeletionSdkTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ACCOUNT = "815670000031";
    private static final Set<String> TERMINAL = Set.of("CREATE_COMPLETE", "CREATE_FAILED",
            "ROLLBACK_COMPLETE", "ROLLBACK_FAILED", "UPDATE_COMPLETE", "UPDATE_ROLLBACK_COMPLETE",
            "UPDATE_ROLLBACK_FAILED", "DELETE_COMPLETE", "DELETE_FAILED");

    @ParameterizedTest
    @ValueSource(strings = {"", "\"DeletionPolicy\":\"Delete\",\"UpdateReplacePolicy\":\"Retain\","})
    void deletionRemovesOnlyTheStackVersionFromAnExternalFunction(String policy) {
        try (Owned owned = new Owned("external-delete", ACCOUNT, Region.US_EAST_1)) {
            String other = owned.seedVersion;
            owned.alias(owned.function, "keep", other);
            owned.create(owned.versionTemplate(policy, false), "CREATE_COMPLETE");
            String version = owned.version("Version");
            assertNotEquals(other, version);
            FunctionConfiguration latest = owned.configuration(owned.function, "$LATEST");
            FunctionConfiguration kept = owned.configuration(owned.function, other);

            owned.delete("DELETE_COMPLETE");

            owned.assertVersionMissing(owned.function, version);
            assertEquals(latest, owned.configuration(owned.function, "$LATEST"));
            assertEquals(kept, owned.configuration(owned.function, other));
            assertEquals(other, owned.lambda.getAlias(request -> request.functionName(owned.function)
                    .name("keep")).functionVersion());
        }
    }

    @Test
    void retainedParentFunctionSurvivesWhileItsDefaultDeleteVersionIsRemoved() {
        try (Owned owned = new Owned("retained-parent", ACCOUNT, Region.US_EAST_1)) {
            String child = owned.marker + "-child";
            owned.functions.add(child);
            String template = """
                    {"Resources":{
                    "Function":{"Type":"AWS::Lambda::Function","DeletionPolicy":"Retain",
                      "Properties":{"FunctionName":"%s","Runtime":"python3.12","Handler":"index.handler",
                      "Role":"%s","Code":{"ZipFile":"def handler(event, context):\\n    return 'owned'\\n"}}},
                    "Version":{"Type":"AWS::Lambda::Version","Properties":{"FunctionName":{"Ref":"Function"}}}}}
                    """.formatted(child, owned.roleArn);
            owned.create(template, "CREATE_COMPLETE");
            String version = owned.version("Version");
            FunctionConfiguration latest = owned.configuration(child, "$LATEST");

            List<StackEvent> deleted = owned.delete("DELETE_COMPLETE");

            assertTrue(deleted.stream().anyMatch(event -> "Function".equals(event.logicalResourceId())
                    && "DELETE_SKIPPED".equals(event.resourceStatusAsString())));
            owned.assertVersionMissing(child, version);
            assertEquals(latest, owned.configuration(child, "$LATEST"));
        }
    }

    @Test
    void retainedVersionStaysPublishedAfterStackDeletion() {
        try (Owned owned = new Owned("retained-version", ACCOUNT, Region.US_EAST_1)) {
            owned.create(owned.versionTemplate("\"DeletionPolicy\":\"Retain\",", false), "CREATE_COMPLETE");
            String version = owned.version("Version");
            FunctionConfiguration before = owned.configuration(owned.function, version);

            List<StackEvent> deleted = owned.delete("DELETE_COMPLETE");

            assertEquals(before, owned.configuration(owned.function, version));
            assertTrue(deleted.stream().anyMatch(event -> "Version".equals(event.logicalResourceId())
                    && "DELETE_SKIPPED".equals(event.resourceStatusAsString())));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "\"DeletionPolicy\":\"Retain\","})
    void removingTheVersionFromTheTemplateHonorsItsDeletionPolicy(String policy) {
        try (Owned owned = new Owned("remove-version", ACCOUNT, Region.US_EAST_1)) {
            owned.create(owned.versionTemplate(policy, true), "CREATE_COMPLETE");
            String version = owned.version("Version");
            FunctionConfiguration before = owned.configuration(owned.function, version);

            List<StackEvent> updated = owned.update(owned.anchorTemplate());

            if (policy.isEmpty()) {
                owned.assertVersionMissing(owned.function, version);
            } else {
                assertEquals(before, owned.configuration(owned.function, version));
            }
            assertTrue(updated.stream().anyMatch(event -> "Version".equals(event.logicalResourceId())
                    && (policy.isEmpty() ? "DELETE_COMPLETE" : "DELETE_SKIPPED")
                            .equals(event.resourceStatusAsString())));
            assertEquals(Set.of("Anchor"), owned.cfn.listStackResources(request -> request.stackName(owned.stackId))
                    .stackResourceSummaries().stream().map(value -> value.logicalResourceId())
                    .collect(Collectors.toSet()));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "\"DeletionPolicy\":\"RetainExceptOnCreate\","})
    void failedCreateRollsBackThePublishedVersion(String policy) {
        try (Owned owned = new Owned("rollback-version", ACCOUNT, Region.US_EAST_1)) {
            Set<String> versions = owned.versions(owned.function);
            String template = """
                    {"Resources":{
                    "Version":{"Type":"AWS::Lambda::Version",%s"Properties":{"FunctionName":"%s"}},
                    "Failure":{"Type":"AWS::Logs::LogStream","DependsOn":"Version",
                      "Properties":{"LogGroupName":"/%s-missing","LogStreamName":"missing"}}}}
                    """.formatted(policy, owned.function, owned.marker);

            List<StackEvent> events = owned.create(template, "ROLLBACK_COMPLETE");

            assertTrue(events.stream().anyMatch(event -> "Version".equals(event.logicalResourceId())
                    && "CREATE_COMPLETE".equals(event.resourceStatusAsString())));
            assertTrue(events.stream().anyMatch(event -> "Failure".equals(event.logicalResourceId())
                    && "CREATE_FAILED".equals(event.resourceStatusAsString())));
            assertTrue(events.stream().anyMatch(event -> "Version".equals(event.logicalResourceId())
                    && "DELETE_COMPLETE".equals(event.resourceStatusAsString())));
            assertEquals(versions, owned.versions(owned.function), "Rollback must remove its published version");
        }
    }

    @Test
    void stackAliasIsDeletedBeforeTheVersionItReferences() {
        try (Owned owned = new Owned("alias-order", ACCOUNT, Region.US_EAST_1)) {
            owned.aliases.add("stack-alias");
            String template = """
                    {"Resources":{
                    "Version":{"Type":"AWS::Lambda::Version","Properties":{"FunctionName":"%s"}},
                    "Alias":{"Type":"AWS::Lambda::Alias","Properties":{"FunctionName":"%s",
                      "Name":"stack-alias","FunctionVersion":{"Fn::GetAtt":["Version","Version"]}}}}}
                    """.formatted(owned.function, owned.function);
            owned.create(template, "CREATE_COMPLETE");
            String version = owned.version("Version");
            assertEquals(version, owned.lambda.getAlias(request -> request.functionName(owned.function)
                    .name("stack-alias")).functionVersion());

            List<StackEvent> deleted = new ArrayList<>(owned.delete("DELETE_COMPLETE").reversed());

            int aliasComplete = eventIndex(deleted, "Alias", "DELETE_COMPLETE");
            int versionStarted = eventIndex(deleted, "Version", "DELETE_IN_PROGRESS");
            assertTrue(aliasComplete < versionStarted, deleted.toString());
            missingLambda(assertThrows(LambdaException.class, () -> owned.lambda.getAlias(request ->
                    request.functionName(owned.function).name("stack-alias"))));
            owned.assertVersionMissing(owned.function, version);
            owned.configuration(owned.function, owned.seedVersion);
        }
    }

    @Test
    void externalAliasBlocksNativeAndStackDeletionUntilTheAliasIsRemoved() {
        try (Owned owned = new Owned("alias-conflict", ACCOUNT, Region.US_EAST_1)) {
            owned.create(owned.versionTemplate("", false), "CREATE_COMPLETE");
            String version = owned.version("Version");
            owned.alias(owned.function, "external", version);
            FunctionConfiguration before = owned.configuration(owned.function, version);
            LambdaException conflict = assertThrows(LambdaException.class, () ->
                    owned.lambda.deleteFunction(request -> request.functionName(owned.function).qualifier(version)));
            assertEquals(409, conflict.statusCode());
            assertEquals("ResourceConflictException", conflict.awsErrorDetails().errorCode());

            List<StackEvent> failed = owned.delete("DELETE_FAILED");

            assertTrue(failed.stream().anyMatch(event -> "Version".equals(event.logicalResourceId())
                    && "DELETE_FAILED".equals(event.resourceStatusAsString())));
            assertEquals(before, owned.configuration(owned.function, version));
            assertEquals(version, owned.lambda.getAlias(request -> request.functionName(owned.function)
                    .name("external")).functionVersion());
            owned.lambda.deleteAlias(request -> request.functionName(owned.function).name("external"));
            owned.delete("DELETE_COMPLETE");
            owned.assertVersionMissing(owned.function, version);
        }
    }

    @Test
    void anAlreadyDeletedVersionAllowsIdempotentStackDeletion() {
        try (Owned owned = new Owned("already-deleted", ACCOUNT, Region.US_EAST_1)) {
            owned.create(owned.versionTemplate("", false), "CREATE_COMPLETE");
            String version = owned.version("Version");
            owned.lambda.deleteFunction(request -> request.functionName(owned.function).qualifier(version));
            owned.assertVersionMissing(owned.function, version);

            owned.delete("DELETE_COMPLETE");

            assertEquals(Set.of("$LATEST", owned.seedVersion), owned.versions(owned.function));
        }
    }

    @Test
    void sameNamedFunctionsAndVersionsInOtherAccountsAndRegionsRemainUnchanged() {
        String shared = "cfn-version-scope-" + UUID.randomUUID().toString().replace("-", "");
        try (Owned owner = new Owned("scope-owner", ACCOUNT, Region.US_EAST_1, shared);
             Owned account = new Owned("scope-account", "815670000032", Region.US_EAST_1, shared);
             Owned region = new Owned("scope-region", ACCOUNT, Region.US_WEST_2, shared)) {
            for (Owned owned : List.of(owner, account, region)) {
                owned.create(owned.versionTemplate("", false), "CREATE_COMPLETE");
            }
            String version = owner.version("Version");
            assertEquals(version, account.version("Version"));
            assertEquals(version, region.version("Version"));
            FunctionConfiguration accountVersion = account.configuration(shared, version);
            FunctionConfiguration regionVersion = region.configuration(shared, version);

            owner.delete("DELETE_COMPLETE");

            owner.assertVersionMissing(shared, version);
            assertEquals(accountVersion, account.configuration(shared, version));
            assertEquals(regionVersion, region.configuration(shared, version));
        }
    }

    private static int eventIndex(List<StackEvent> events, String logical, String status) {
        for (int index = 0; index < events.size(); index++) {
            StackEvent event = events.get(index);
            if (logical.equals(event.logicalResourceId()) && status.equals(event.resourceStatusAsString())) {
                return index;
            }
        }
        throw new AssertionError("Missing " + logical + " " + status + ": " + events);
    }

    private static Set<String> ids(List<StackEvent> events) {
        return events.stream().map(StackEvent::eventId).collect(Collectors.toSet());
    }

    private static byte[] zip(String marker) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(output)) {
                zip.putNextEntry(new ZipEntry("handler.py"));
                zip.write(("def handler(event, context):\n    return '" + marker + "'\n")
                        .getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            return output.toByteArray();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static final class Owned implements AutoCloseable {
        private final WireReceipt wire = new WireReceipt();
        private final String marker = "cfn-version-" + UUID.randomUUID().toString().replace("-", "");
        private final Set<String> functions = new LinkedHashSet<>();
        private final Set<String> aliases = new LinkedHashSet<>();
        private final String caseName;
        private final String function;
        private CloudFormationClient cfn;
        private LambdaClient lambda;
        private IamClient iam;
        private String roleArn;
        private String seedVersion;
        private String stackId;
        private boolean roleAttempted;
        private boolean stackAttempted;
        private boolean stackDeleted;

        private Owned(String caseName, String account, Region region) {
            this(caseName, account, region, null);
        }

        private Owned(String caseName, String account, Region region, String sharedFunction) {
            this.caseName = caseName;
            function = sharedFunction == null ? marker : sharedFunction;
            try {
                cfn = AwsTestClients.create(CloudFormationClient.builder().overrideConfiguration(config ->
                        config.apiCallTimeout(Duration.ofSeconds(15)).apiCallAttemptTimeout(Duration.ofSeconds(10))
                                .retryPolicy(RetryPolicy.none()).addExecutionInterceptor(wire)), account, region);
                lambda = AwsTestClients.create(LambdaClient.builder().overrideConfiguration(config ->
                        config.apiCallTimeout(Duration.ofSeconds(15)).apiCallAttemptTimeout(Duration.ofSeconds(10))
                                .retryPolicy(RetryPolicy.none()).addExecutionInterceptor(wire)), account, region);
                iam = AwsTestClients.create(IamClient.builder().overrideConfiguration(config ->
                        config.apiCallTimeout(Duration.ofSeconds(15)).apiCallAttemptTimeout(Duration.ofSeconds(10))
                                .retryPolicy(RetryPolicy.none()).addExecutionInterceptor(wire)), account, region);
                roleAttempted = true;
                roleArn = iam.createRole(request -> request.roleName(marker).assumeRolePolicyDocument("""
                        {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                        "Principal":{"Service":"lambda.amazonaws.com"},"Action":"sts:AssumeRole"}]}
                        """)).role().arn();
                functions.add(function);
                lambda.createFunction(request -> request.functionName(function).runtime(Runtime.PYTHON3_12)
                        .handler("handler.handler").role(roleArn)
                        .code(code -> code.zipFile(SdkBytes.fromByteArray(zip("seed")))));
                awaitFunctionReady();
                seedVersion = lambda.publishVersion(request -> request.functionName(function)).version();
                lambda.updateFunctionCode(request -> request.functionName(function)
                        .zipFile(SdkBytes.fromByteArray(zip("stack"))));
                awaitFunctionReady();
            } catch (RuntimeException | Error failure) {
                try {
                    close();
                } catch (RuntimeException | Error cleanupFailure) {
                    if (cleanupFailure != failure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
                throw failure;
            }
        }

        private String versionTemplate(String policy, boolean anchor) {
            return """
                    {"Resources":{"Version":{"Type":"AWS::Lambda::Version",%s
                    "Properties":{"FunctionName":"%s"}}%s}}
                    """.formatted(policy, function, anchor ? "," + anchorResource() : "");
        }

        private String anchorResource() {
            aliases.add("anchor");
            return """
                    "Anchor":{"Type":"AWS::Lambda::Alias","Properties":{"FunctionName":"%s",
                    "Name":"anchor","FunctionVersion":"%s"}}
                    """.formatted(function, seedVersion);
        }

        private String anchorTemplate() {
            return "{\"Resources\":{" + anchorResource() + "}}";
        }

        private void alias(String functionName, String name, String version) {
            assertEquals(function, functionName);
            aliases.add(name);
            lambda.createAlias(request -> request.functionName(functionName).name(name).functionVersion(version));
        }

        private FunctionConfiguration configuration(String name, String version) {
            return lambda.getFunction(request -> request.functionName(name).qualifier(version)).configuration();
        }

        private void awaitFunctionReady() {
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline) {
                FunctionConfiguration current = configuration(function, "$LATEST");
                assertNotEquals("Failed", current.stateAsString(), current.toString());
                assertNotEquals("Failed", current.lastUpdateStatusAsString(), current.toString());
                if ("Active".equals(current.stateAsString())
                        && (current.lastUpdateStatusAsString() == null
                        || "Successful".equals(current.lastUpdateStatusAsString()))) {
                    return;
                }
                pause();
            }
            throw new AssertionError("Owned function did not become ready before publishing");
        }

        private Set<String> versions(String name) {
            Set<String> result = new HashSet<>();
            Set<String> cursors = new HashSet<>();
            String cursor = null;
            for (int page = 0; page < 20; page++) {
                String next = cursor;
                ListVersionsByFunctionResponse response = lambda.listVersionsByFunction(request ->
                        request.functionName(name).marker(next));
                for (FunctionConfiguration version : response.versions()) {
                    assertTrue(result.add(version.version()), "Duplicate version");
                }
                cursor = response.nextMarker();
                if (cursor == null) {
                    return result;
                }
                assertTrue(cursors.add(cursor), "Repeated version cursor");
            }
            throw new AssertionError("Owned version inventory exceeded twenty pages");
        }

        private void assertVersionMissing(String name, String version) {
            missingLambda(assertThrows(LambdaException.class, () -> configuration(name, version)));
            assertFalse(versions(name).contains(version));
        }

        private String version(String logical) {
            String physical = cfn.describeStackResource(request -> request.stackName(stackId)
                    .logicalResourceId(logical)).stackResourceDetail().physicalResourceId();
            assertTrue(physical.startsWith("arn:") && physical.contains(":lambda:"));
            String version = physical.substring(physical.lastIndexOf(':') + 1);
            assertTrue(version.matches("[0-9]+"));
            return version;
        }

        private List<StackEvent> create(String template, String expected) {
            stackAttempted = true;
            stackId = cfn.createStack(request -> request.stackName(marker).templateBody(template)).stackId();
            return awaitEvents(Set.of(), expected);
        }

        private List<StackEvent> update(String template) {
            Set<String> before = ids(events());
            cfn.updateStack(request -> request.stackName(stackId).templateBody(template));
            return awaitEvents(before, "UPDATE_COMPLETE");
        }

        private List<StackEvent> delete(String expected) {
            Set<String> before = ids(events());
            cfn.deleteStack(request -> request.stackName(stackId));
            List<StackEvent> result = awaitEvents(before, expected);
            if ("DELETE_COMPLETE".equals(expected)) {
                missingStack(assertThrows(CloudFormationException.class, () ->
                        cfn.describeStacks(request -> request.stackName(marker))), marker);
                stackDeleted = true;
            }
            return result;
        }

        private List<StackEvent> events() {
            List<StackEvent> result = new ArrayList<>();
            Set<String> cursors = new HashSet<>();
            String cursor = null;
            for (int page = 0; page < 20; page++) {
                String next = cursor;
                DescribeStackEventsResponse response = cfn.describeStackEvents(request ->
                        request.stackName(stackId).nextToken(next));
                result.addAll(response.stackEvents());
                cursor = response.nextToken();
                if (cursor == null) {
                    assertEquals(result.size(), ids(result).size(), "Duplicate event ids");
                    return result;
                }
                assertTrue(cursors.add(cursor), "Repeated event cursor");
            }
            throw new AssertionError("Owned event history exceeded twenty pages");
        }

        private List<StackEvent> awaitEvents(Set<String> before, String expected) {
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            List<StackEvent> latest = List.of();
            while (System.nanoTime() < deadline) {
                latest = events().stream().filter(event -> !before.contains(event.eventId())).toList();
                for (StackEvent event : latest) {
                    if (stackId.equals(event.stackId()) && stackId.equals(event.physicalResourceId())
                            && marker.equals(event.logicalResourceId())
                            && "AWS::CloudFormation::Stack".equals(event.resourceType())
                            && TERMINAL.contains(event.resourceStatusAsString())) {
                        assertEquals(expected, event.resourceStatusAsString(), latest.toString());
                        assertEquals(expected, cfn.describeStacks(request -> request.stackName(stackId))
                                .stacks().getFirst().stackStatusAsString());
                        return latest;
                    }
                }
                pause();
            }
            throw new AssertionError("Owned stack did not reach " + expected + ": " + latest);
        }

        private void cleanupStack() {
            if (!stackAttempted || stackDeleted) {
                return;
            }
            String lookup = stackId == null ? marker : stackId;
            try {
                stackId = cfn.describeStacks(request -> request.stackName(lookup)).stacks().getFirst().stackId();
            } catch (CloudFormationException missing) {
                missingStack(missing, lookup);
                stackDeleted = true;
                return;
            }
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (System.nanoTime() < deadline) {
                String status = cfn.describeStacks(request -> request.stackName(stackId))
                        .stacks().getFirst().stackStatusAsString();
                if (TERMINAL.contains(status)) {
                    if ("DELETE_COMPLETE".equals(status)) {
                        missingStack(assertThrows(CloudFormationException.class, () ->
                                cfn.describeStacks(request -> request.stackName(marker))), marker);
                        stackDeleted = true;
                    } else {
                        delete("DELETE_COMPLETE");
                    }
                    return;
                }
                pause();
            }
            throw new AssertionError("Owned stack remained in progress during cleanup");
        }

        @Override
        public void close() {
            IllegalStateException failures = new IllegalStateException("Owned Lambda version fixture cleanup failed");
            if (lambda != null) {
                for (String alias : aliases) {
                    cleanup(() -> {
                        try {
                            lambda.deleteAlias(request -> request.functionName(function).name(alias));
                        } catch (LambdaException missing) {
                            missingLambda(missing);
                        }
                    }, failures);
                }
            }
            if (cfn != null) {
                cleanup(this::cleanupStack, failures);
            }
            if (lambda != null) {
                for (String name : functions) {
                    cleanup(() -> {
                        try {
                            lambda.deleteFunction(request -> request.functionName(name));
                        } catch (LambdaException missing) {
                            missingLambda(missing);
                        }
                        missingLambda(assertThrows(LambdaException.class, () ->
                                lambda.getFunction(request -> request.functionName(name))));
                    }, failures);
                }
            }
            if (iam != null && roleAttempted) {
                cleanup(() -> {
                    try {
                        iam.deleteRole(request -> request.roleName(marker));
                    } catch (IamException missing) {
                        missingRole(missing);
                    }
                    missingRole(assertThrows(IamException.class, () ->
                            iam.getRole(request -> request.roleName(marker))));
                }, failures);
            }
            if (cfn != null) {
                cleanup(cfn::close, failures);
            }
            if (lambda != null) {
                cleanup(lambda::close, failures);
            }
            if (iam != null) {
                cleanup(iam::close, failures);
            }
            cleanup(() -> wire.save(caseName, marker, failures.getSuppressed().length == 0), failures);
            if (failures.getSuppressed().length > 0) {
                throw failures;
            }
        }
    }

    private static void missingLambda(LambdaException failure) {
        assertEquals(404, failure.statusCode());
        assertEquals("ResourceNotFoundException", failure.awsErrorDetails().errorCode());
    }

    private static void missingRole(IamException failure) {
        assertEquals(404, failure.statusCode());
        assertEquals("NoSuchEntity", failure.awsErrorDetails().errorCode());
    }

    private static void missingStack(CloudFormationException failure, String lookup) {
        assertEquals(400, failure.statusCode());
        assertEquals("ValidationError", failure.awsErrorDetails().errorCode());
        assertEquals("Stack with id " + lookup + " does not exist", failure.awsErrorDetails().errorMessage());
    }

    private static void cleanup(Runnable action, IllegalStateException failures) {
        try {
            action.run();
        } catch (RuntimeException | Error failure) {
            if (failure != failures) {
                failures.addSuppressed(failure);
            }
        }
    }

    private static void pause() {
        try {
            Thread.sleep(25);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }

    private static final class WireReceipt implements ExecutionInterceptor {
        private static final ExecutionAttribute<Integer> ID = new ExecutionAttribute<>("lambda-version-cfn-wire-id");
        private final List<Map<String, Object>> calls = new ArrayList<>();

        @Override
        public void beforeTransmission(Context.BeforeTransmission context, ExecutionAttributes attributes) {
            assertEquals("localhost", context.httpRequest().getUri().getHost());
            assertEquals("http", context.httpRequest().getUri().getScheme());
            assertTrue(calls.size() < 3000);
            attributes.putAttribute(ID, calls.size());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", calls.size());
            row.put("operation", context.request().getClass().getSimpleName().replaceFirst("Request$", ""));
            row.put("method", context.httpRequest().method().name());
            row.put("uri", context.httpRequest().getUri().toString());
            row.put("requestModel", model(context.request()));
            Map<String, List<String>> headers = new LinkedHashMap<>(context.httpRequest().headers());
            headers.keySet().removeIf(name -> name.equalsIgnoreCase("authorization")
                    || name.equalsIgnoreCase("x-amz-security-token"));
            row.put("requestHeaders", headers);
            row.put("requestBodyAvailable", context.requestBody().isPresent());
            try (InputStream stream = context.requestBody().isPresent()
                    ? context.requestBody().orElseThrow().contentStreamProvider().newStream()
                    : InputStream.nullInputStream()) {
                row.put("requestBase64", encode(stream));
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
            calls.add(row);
        }

        @Override
        public Optional<InputStream> modifyHttpResponseContent(Context.ModifyHttpResponse context,
                                                               ExecutionAttributes attributes) {
            Map<String, Object> row = calls.get(attributes.getAttribute(ID));
            row.put("status", context.httpResponse().statusCode());
            row.put("responseHeaders", context.httpResponse().headers());
            try (InputStream stream = context.responseBody().orElseGet(InputStream::nullInputStream)) {
                String encoded = encode(stream);
                row.put("responseBase64", encoded);
                return Optional.of(new ByteArrayInputStream(Base64.getDecoder().decode(encoded)));
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }

        @Override
        public void afterExecution(Context.AfterExecution context, ExecutionAttributes attributes) {
            calls.get(attributes.getAttribute(ID)).put("responseModel", model(context.response()));
        }

        @Override
        public void onExecutionFailure(Context.FailedExecution context, ExecutionAttributes attributes) {
            Integer id = attributes.getAttribute(ID);
            if (id == null) {
                return;
            }
            Map<String, Object> row = calls.get(id);
            row.put("exceptionType", context.exception().getClass().getName());
            if (context.exception() instanceof AwsServiceException failure) {
                row.put("errorCode", failure.awsErrorDetails().errorCode());
                row.put("errorMessage", failure.awsErrorDetails().errorMessage());
                row.put("errorStatus", failure.statusCode());
            }
        }

        private static Object model(Object value) {
            if (value instanceof SdkPojo pojo) {
                Map<String, Object> fields = new LinkedHashMap<>();
                pojo.sdkFields().forEach(field -> {
                    Object member = field.getValueOrDefault(pojo);
                    if (member != null) {
                        fields.put(field.memberName(), model(member));
                    }
                });
                return fields;
            }
            if (value instanceof List<?> list) {
                return list.stream().map(WireReceipt::model).toList();
            }
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> fields = new LinkedHashMap<>();
                map.forEach((key, member) -> fields.put(key.toString(), model(member)));
                return fields;
            }
            if (value instanceof SdkBytes bytes) {
                return Base64.getEncoder().encodeToString(bytes.asByteArray());
            }
            if (value instanceof String || value instanceof Number || value instanceof Boolean) {
                return value;
            }
            return value.toString();
        }

        private static String encode(InputStream stream) throws IOException {
            byte[] bytes = stream.readNBytes(1_048_577);
            assertTrue(bytes.length <= 1_048_576);
            return Base64.getEncoder().encodeToString(bytes);
        }

        private void save(String caseName, String marker, boolean cleanupComplete) {
            try {
                Path directory = Path.of(System.getProperty("audit.lambda.version.cfn.directory",
                        "target/lambda-version-cfn-wire"));
                Files.createDirectories(directory);
                MAPPER.writerWithDefaultPrettyPrinter().writeValue(directory.resolve(marker + ".json").toFile(),
                        Map.of("case", caseName, "marker", marker, "cleanupComplete", cleanupComplete, "calls", calls));
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
            assertFalse(calls.isEmpty());
            assertTrue(calls.stream().allMatch(row -> row.containsKey("responseBase64")),
                    "every attempted transmission has a complete captured response");
        }
    }
}
