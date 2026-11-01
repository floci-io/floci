package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.appconfig.AppConfigService;
import io.github.hectorvent.floci.services.appconfig.model.Application;
import io.github.hectorvent.floci.services.appconfig.model.Deployment;
import io.github.hectorvent.floci.services.appconfig.model.DeploymentSummary;
import io.github.hectorvent.floci.services.appconfig.model.HostedConfigurationVersion;
import io.github.hectorvent.floci.services.cloudformation.model.Stack;
import io.github.hectorvent.floci.services.cloudformation.provisioners.AppConfigCfnProvisioner;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CfnDynamicReferences;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CfnResourceDispatcher;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CloudFormationResourceRegistry;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.ssm.SsmService;
import io.github.hectorvent.floci.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * AppConfig resources through the real stack engine, for the paths that need it: the order the
 * engine deletes a removed application and its displaced children in, a ContinueUpdateRollback
 * after an in-place undo that failed, and what earlier deployments report once stack updates change
 * or replace the strategy and profile they used. The AppConfig service runs on in-memory storage, whose stores
 * the test also reads directly to see what the API can no longer reach.
 */
class CloudFormationAppConfigLifecycleTest {

    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private InMemoryStorageFactory storage;
    private AppConfigService appConfig;
    private CloudFormationService cfn;
    private int changeSets;

    @BeforeEach
    void setUp() {
        storage = new InMemoryStorageFactory(ACCOUNT);
        appConfig = spy(new AppConfigService(storage, null));
        CfnResourceDispatcher dispatcher = new CfnResourceDispatcher(MAPPER,
                new CloudFormationResourceRegistry(List.of(new AppConfigCfnProvisioner(appConfig))), null, null);
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn(ACCOUNT);
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn(ACCOUNT);
        cfn = new CloudFormationService(dispatcher, mock(S3Service.class), mock(SsmService.class),
                mock(GetStackOutputRoleAuthorizer.class), new CfnDynamicReferences(null, null, MAPPER), MAPPER,
                config, regionResolver, Clock.systemUTC(), new InMemoryStorageFactory(ACCOUNT));
    }

    /**
     * Renaming the application's logical id replaces the environment and profile that refer to it.
     * The engine deletes the removed application before the displaced children, so their deletes
     * must not depend on finding it.
     */
    @Test
    void childrenMovedToANewApplicationAreDeletedAfterTheOldApplicationIsRemoved() throws Exception {
        String children = """
                "Env":{"Type":"AWS::AppConfig::Environment","Properties":{"ApplicationId":{"Ref":"%1$s"},"Name":"beta"}},
                "Profile":{"Type":"AWS::AppConfig::ConfigurationProfile","Properties":{
                  "ApplicationId":{"Ref":"%1$s"},"Name":"settings","LocationUri":"hosted"}}""";
        deploy("moved", "CREATE", "{\"Resources\":{\"AppA\":{\"Type\":\"AWS::AppConfig::Application\","
                + "\"Properties\":{\"Name\":\"orders\"}}," + children.formatted("AppA") + "}}");
        Stack stack = stack("moved");
        String oldApp = stack.getResources().get("AppA").getPhysicalId();
        String oldEnv = stack.getResources().get("Env").getPhysicalId();
        String oldProfile = stack.getResources().get("Profile").getPhysicalId();
        appConfig.createHostedConfigurationVersion(oldApp, oldProfile, "{}".getBytes(StandardCharsets.UTF_8),
                "application/json", null);
        appConfig.startDeployment(oldApp, oldEnv, Map.of("ConfigurationProfileId", oldProfile,
                "ConfigurationVersion", "1", "DeploymentStrategyId", "AppConfig.AllAtOnce"));

        deploy("moved", "UPDATE", "{\"Resources\":{\"AppB\":{\"Type\":\"AWS::AppConfig::Application\","
                + "\"Properties\":{\"Name\":\"orders\"}}," + children.formatted("AppB") + "}}");

        stack = stack("moved");
        assertEquals("UPDATE_COMPLETE", stack.getStatus(), stack.getStatusReason());
        assertNotEquals(oldEnv, stack.getResources().get("Env").getPhysicalId());
        assertTrue(appConfig.listApplications().stream().noneMatch(app -> app.getId().equals(oldApp)));
        assertTrue(appConfig.findEnvironment(oldEnv).isEmpty(), "the displaced environment is deleted");
        assertTrue(appConfig.findConfigurationProfile(oldProfile).isEmpty(), "the displaced profile is deleted");
        assertTrue(store("appconfig-deployments.json", new TypeReference<Map<String, Deployment>>() {})
                .scan(key -> key.startsWith(oldApp + "::")).isEmpty(), "with its deployments");
        assertTrue(store("appconfig-versions.json", new TypeReference<Map<String, HostedConfigurationVersion>>() {})
                .scan(key -> key.startsWith(oldApp + "::")).isEmpty(), "and its hosted configuration versions");
    }

    /**
     * An in-place update whose undo fails ends the stack in UPDATE_ROLLBACK_FAILED. The
     * ContinueUpdateRollback that follows must put back the state the update started from, the
     * last committed one, not an older state an earlier committed update had replaced.
     */
    @Test
    void continueUpdateRollbackAfterAFailedUndoRestoresTheLastCommittedState() throws Exception {
        String template = "{\"Resources\":{\"App\":{\"Type\":\"AWS::AppConfig::Application\",\"Properties\":"
                + "{\"Name\":\"%s\",\"Tags\":[{\"Key\":\"stage\",\"Value\":\"%s\"}]}}}}";
        deploy("retried", "CREATE", template.formatted("first", "1"));
        String app = stack("retried").getResources().get("App").getPhysicalId();
        deploy("retried", "UPDATE", template.formatted("second", "2"));
        assertEquals("UPDATE_COMPLETE", stack("retried").getStatus());

        doThrow(new AwsException("InternalServerException", "tag store down", 500))
                .when(appConfig).setApplicationTags(anyString(), any());
        deploy("retried", "UPDATE", template.formatted("third", "3"));
        assertEquals("UPDATE_ROLLBACK_FAILED", stack("retried").getStatus());

        doCallRealMethod().when(appConfig).setApplicationTags(anyString(), any());
        cfn.continueUpdateRollback("retried", List.of(), false, REGION).get(30, TimeUnit.SECONDS);

        assertEquals("UPDATE_ROLLBACK_COMPLETE", stack("retried").getStatus(), stack("retried").getStatusReason());
        Application restored = appConfig.getApplication(app);
        assertEquals("second", restored.getName());
        assertEquals(Map.of("stage", "2"), restored.getTags());
    }

    /**
     * A deployment reports the profile name and strategy settings it started with: an in-place stack
     * update that renames the profile and slows the strategy leaves it as it was, and so does a
     * replacement that deletes the strategy, after which ListDeployments still answers.
     */
    @Test
    void earlierDeploymentsKeepTheirSettingsThroughInPlaceUpdatesAndAStrategyReplacement() throws Exception {
        String template = """
                {"Resources":{
                  "App":{"Type":"AWS::AppConfig::Application","Properties":{"Name":"orders"}},
                  "Env":{"Type":"AWS::AppConfig::Environment","Properties":{"ApplicationId":{"Ref":"App"},"Name":"beta"}},
                  "Profile":{"Type":"AWS::AppConfig::ConfigurationProfile","Properties":{
                    "ApplicationId":{"Ref":"App"},"Name":"%s","LocationUri":"hosted"}},
                  "Strategy":{"Type":"AWS::AppConfig::DeploymentStrategy","Properties":{
                    "Name":"%s","ReplicateTo":"NONE","DeploymentDurationInMinutes":%d,"GrowthFactor":%d}}
                }}""";
        deploy("history", "CREATE", template.formatted("settings", "gradual", 0, 100));
        Stack stack = stack("history");
        String app = stack.getResources().get("App").getPhysicalId();
        String env = stack.getResources().get("Env").getPhysicalId();
        String profile = stack.getResources().get("Profile").getPhysicalId();
        String strategy = stack.getResources().get("Strategy").getPhysicalId();
        appConfig.createHostedConfigurationVersion(app, profile, "{}".getBytes(StandardCharsets.UTF_8),
                "application/json", null);
        appConfig.startDeployment(app, env, Map.of("ConfigurationProfileId", profile, "ConfigurationVersion", "1",
                "DeploymentStrategyId", strategy));

        deploy("history", "UPDATE", template.formatted("renamed", "gradual", 10, 20));
        assertEquals("UPDATE_COMPLETE", stack("history").getStatus(), stack("history").getStatusReason());
        assertEquals(strategy, stack("history").getResources().get("Strategy").getPhysicalId(), "changed in place");
        assertStartedWith(app, env);

        deploy("history", "UPDATE", template.formatted("renamed", "faster", 10, 20));
        assertEquals("UPDATE_COMPLETE", stack("history").getStatus(), stack("history").getStatusReason());
        assertNotEquals(strategy, stack("history").getResources().get("Strategy").getPhysicalId(), "replaced");
        assertStartedWith(app, env);
    }

    private void assertStartedWith(String app, String env) {
        DeploymentSummary deployment = appConfig.listDeployments(app, env, null, null).items().getFirst();
        assertEquals("settings", deployment.getConfigurationName());
        assertEquals(0, deployment.getDeploymentDurationInMinutes());
        assertEquals(100f, deployment.getGrowthFactor());
    }

    private <V> StorageBackend<String, V> store(String fileName, TypeReference<Map<String, V>> type) {
        return storage.create("appconfig", fileName, type);
    }

    private void deploy(String name, String type, String template) throws Exception {
        String changeSet = "change-" + (++changeSets);
        cfn.createChangeSet(name, changeSet, type, template, null, Map.of(), List.of(), Map.of(), REGION, ACCOUNT);
        cfn.executeChangeSet(name, changeSet, REGION, ACCOUNT).get(30, TimeUnit.SECONDS);
    }

    private Stack stack(String name) {
        return cfn.describeStacks(name, REGION, ACCOUNT).getFirst();
    }
}
