package io.floci.aws.services.ssm;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.ssm.model.Command;
import io.floci.aws.services.ssm.model.CommandInvocation;
import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.function.Executable;

import java.net.URI;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.reset;

abstract class SsmCommandIntegrationFixture {
    @Inject protected StorageFactory storageFactory;
    @Inject protected SsmCommandService commandService;
    @InjectMock protected SsmDirectCommandExecutor executor;
    @TestHTTPResource protected URI endpoint;

    @BeforeEach
    void setupSsmFixture() {
        RestAssuredJsonUtils.configureAwsContentTypes();
        reset(executor);
    }

    protected Response call(String account, String region, String target, Object body) {
        return given().port(endpoint.getPort()).contentType("application/x-amz-json-1.1")
                .header("Authorization", EnforcementFixtures.auth(account, region, "ssm"))
                .header("X-Amz-Target", target).body(body).post("/");
    }

    protected void cleanupCommands(String account, String region, String instance) {
        RequestScopes.runAs(account, region, () -> {
            List<Command> owned = commandService.listCommands(null, instance, region);
            assertAll("remove queued and polled correlations and every owned record",
                    () -> commandService.failActiveInvocationsForInstance(region, instance, "fixture cleanup"),
                    () -> assertAll(owned.stream().map(command -> (Executable) () ->
                            deleteCommandRecords(account, region, command.getCommandId(), instance))));
        });
    }

    protected void deleteCommand(String account, String region, String command, String instance) {
        assertAll("remove owned queue, command and invocation",
                () -> RequestScopes.runAs(account, region, () ->
                        commandService.failActiveInvocationsForInstance(region, instance, "fixture cleanup")),
                () -> deleteCommandRecords(account, region, command, instance));
    }

    private void deleteCommandRecords(String account, String region, String command, String instance) {
        AccountAwareStorageBackend<Command> commands = storageFactory.create(
                "ssm", "ssm-commands.json", new TypeReference<>() {});
        AccountAwareStorageBackend<CommandInvocation> invocations = storageFactory.create(
                "ssm", "ssm-invocations.json", new TypeReference<>() {});
        String key = region + "::" + command;
        assertAll("remove both owned records",
                () -> {
                    commands.deleteForAccount(account, key);
                    assertTrue(commands.getForAccount(account, key).isEmpty());
                },
                () -> {
                    invocations.deleteForAccount(account, key + "::" + instance);
                    assertTrue(invocations.getForAccount(account, key + "::" + instance).isEmpty());
                });
    }
}
