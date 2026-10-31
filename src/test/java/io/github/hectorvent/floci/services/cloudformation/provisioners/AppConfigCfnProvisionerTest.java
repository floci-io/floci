package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.appconfig.AppConfigService;
import io.github.hectorvent.floci.services.appconfig.model.Application;
import io.github.hectorvent.floci.services.appconfig.model.ConfigurationProfile;
import io.github.hectorvent.floci.services.appconfig.model.Deployment;
import io.github.hectorvent.floci.services.appconfig.model.DeploymentStrategy;
import io.github.hectorvent.floci.services.appconfig.model.Environment;
import io.github.hectorvent.floci.services.appconfig.model.HostedConfigurationVersion;
import io.github.hectorvent.floci.services.appconfig.model.Monitor;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * {@code AWS::AppConfig::*} against a real AppConfig service on in-memory storage: the physical id
 * is the AppConfig ID and the ID attribute, mutable properties change in place and roll back from
 * the snapshot, a create-only change replaces the entity, and deletes go by exact ID.
 */
class AppConfigCfnProvisionerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String STACK = "configuration";
    private static final String APPLICATION = "AWS::AppConfig::Application";
    private static final String ENVIRONMENT = "AWS::AppConfig::Environment";
    private static final String PROFILE = "AWS::AppConfig::ConfigurationProfile";
    private static final String STRATEGY = "AWS::AppConfig::DeploymentStrategy";
    private static final String ALARM = "arn:aws:cloudwatch:us-east-1:000000000000:alarm:configuration-errors";
    private static final String ALARM_ROLE = "arn:aws:iam::000000000000:role/appconfig-monitor";

    private InMemoryStorageFactory storage;
    private AppConfigService service;
    private AppConfigCfnProvisioner provisioner;
    private CloudFormationTemplateEngine engine;

    @BeforeEach
    void setUp() {
        storage = new InMemoryStorageFactory(ACCOUNT);
        service = spy(new AppConfigService(storage, null));
        provisioner = new AppConfigCfnProvisioner(service);
        engine = CloudFormationTemplateEngine.standalone(ACCOUNT, REGION, STACK, MAPPER, value -> value);
    }

    @Test
    void anApplicationIsCreatedWithItsTagsAndItsIdIsTheRefAndTheAttribute() {
        StackResource app = create(APPLICATION, props("Name", "orders", "Description", "Order service",
                "Tags", List.of(tag("team", "payments"))));

        Application stored = service.getApplication(app.getPhysicalId());
        assertEquals(app.getPhysicalId(), app.getAttributes().get("ApplicationId"));
        assertEquals("orders", stored.getName());
        assertEquals("Order service", stored.getDescription());
        assertEquals(Map.of("team", "payments"), stored.getTags());
    }

    @Test
    void anApplicationUpdateChangesItInPlaceAndARollbackPutsItBack() {
        StackResource app = create(APPLICATION, props("Name", "orders", "Description", "Order service",
                "Tags", List.of(tag("team", "payments"), tag("tier", "gold"))));
        String id = app.getPhysicalId();

        update(app, props("Name", "orders-v2", "Tags", List.of(tag("team", "billing"), tag("owner", "ops"))));

        assertEquals(id, app.getPhysicalId());
        Application updated = service.getApplication(id);
        assertEquals("orders-v2", updated.getName());
        assertNull(updated.getDescription(), "a Description the template drops is cleared");
        assertEquals(Map.of("team", "billing", "owner", "ops"), updated.getTags(), "tags follow the template");

        assertTrue(provisioner.rollbackUpdate(app));
        Application restored = service.getApplication(id);
        assertEquals("orders", restored.getName());
        assertEquals("Order service", restored.getDescription());
        assertEquals(Map.of("team", "payments", "tier", "gold"), restored.getTags());
        assertFalse(app.getAttributes().containsKey(CfnRollback.APPCONFIG_UPDATE_SNAPSHOT_ATTR));
    }

    @Test
    void aCreateWhoseTaggingFailsDeletesTheApplication() {
        doThrow(new AwsException("InternalServerException", "tagging failed", 500))
                .when(service).setApplicationTags(anyString(), any());
        StackResource app = resource(APPLICATION);

        AwsException failure = assertThrows(AwsException.class, () -> provisioner.provision(app,
                MAPPER.valueToTree(props("Name", "orders", "Tags", List.of(tag("team", "payments")))), context(null)));

        assertEquals("tagging failed", failure.getMessage());
        assertTrue(service.listApplications().isEmpty(), "the application the failed create made is deleted");
        assertFalse(app.getAttributes().containsKey(CfnRollback.ROLLBACK_OWNED_ATTR));
    }

    @Test
    void anUpdateWhoseTaggingFailsPutsTheNameBack() {
        StackResource app = create(APPLICATION, props("Name", "orders", "Tags", List.of(tag("team", "payments"))));
        // Only the update's own tag call fails; the undo's succeeds.
        doThrow(new AwsException("InternalServerException", "tagging failed", 500)).doCallRealMethod()
                .when(service).setApplicationTags(anyString(), any());

        assertThrows(AwsException.class, () -> update(app, props("Name", "orders-v2",
                "Tags", List.of(tag("team", "billing")))));

        Application restored = service.getApplication(app.getPhysicalId());
        assertEquals("orders", restored.getName());
        assertEquals(Map.of("team", "payments"), restored.getTags());
        assertFalse(app.getAttributes().containsKey(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR),
                "the undo completed, so the stack can report the prior application intact");
    }

    @Test
    void anUndoThatFailsTooIsRecordedForTheRollback() {
        StackResource app = create(APPLICATION, props("Name", "orders", "Tags", List.of(tag("team", "payments"))));
        doThrow(new AwsException("InternalServerException", "tagging failed", 500))
                .when(service).setApplicationTags(anyString(), any());

        AwsException failure = assertThrows(AwsException.class, () -> update(app, props("Name", "orders-v2",
                "Tags", List.of(tag("team", "billing")))));

        assertEquals("tagging failed", failure.getMessage(), "the stack reports the update's own failure");
        assertTrue(app.getAttributes().get(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR)
                .startsWith("Could not restore " + app.getPhysicalId()));
    }

    /**
     * The engine puts the previous resource back over an attempt whose undo failed; that resource
     * then carries the attempt's snapshot, the state the update started from, in place of the one an
     * earlier committed update left on it, so a ContinueUpdateRollback restores the right state.
     */
    @Test
    void anUndoThatFailedLeavesItsSnapshotOnTheRestoredResource() {
        StackResource app = create(APPLICATION, props("Name", "first", "Tags", List.of(tag("stage", "1"))));
        update(app, props("Name", "second", "Tags", List.of(tag("stage", "2"))));
        StackResource committed = copy(app);
        StackResource attempt = copy(committed);
        doThrow(new AwsException("InternalServerException", "tagging failed", 500))
                .when(service).setApplicationTags(anyString(), any());
        assertThrows(AwsException.class, () -> update(attempt, props("Name", "third",
                "Tags", List.of(tag("stage", "3")))));

        provisioner.mergeFailedUpdateResourceTracking(committed, attempt);
        doCallRealMethod().when(service).setApplicationTags(anyString(), any());
        assertTrue(provisioner.rollbackUpdate(committed));

        Application restored = service.getApplication(app.getPhysicalId());
        assertEquals("second", restored.getName());
        assertEquals(Map.of("stage", "2"), restored.getTags());
    }

    @Test
    void anUndoThatCompletedLeavesTheRestoredResourceAlone() {
        StackResource app = create(APPLICATION, props("Name", "first"));
        StackResource committed = copy(app);
        StackResource attempt = copy(committed);
        update(attempt, props("Name", "second"));

        provisioner.mergeFailedUpdateResourceTracking(committed, attempt);

        assertEquals(app.getAttributes(), committed.getAttributes());
    }

    @Test
    void anEnvironmentKeepsItsMonitorsAndChangesInPlace() {
        String appId = create(APPLICATION, props("Name", "orders")).getPhysicalId();
        StackResource env = create(ENVIRONMENT, props("ApplicationId", appId, "Name", "beta",
                "Monitors", List.of(Map.of("AlarmArn", ALARM, "AlarmRoleArn", ALARM_ROLE))));
        String id = env.getPhysicalId();
        assertEquals(id, env.getAttributes().get("EnvironmentId"));
        assertEquals(List.of(new Monitor(ALARM, ALARM_ROLE)), service.getEnvironment(appId, id).getMonitors());

        update(env, props("ApplicationId", appId, "Name", "gamma", "Description", "Second stage"));

        assertEquals(id, env.getPhysicalId());
        Environment updated = service.getEnvironment(appId, id);
        assertEquals("gamma", updated.getName());
        assertEquals("Second stage", updated.getDescription());
        assertNull(updated.getMonitors(), "Monitors the template drops are cleared");

        provisioner.rollbackUpdate(env);
        Environment restored = service.getEnvironment(appId, id);
        assertEquals("beta", restored.getName());
        assertNull(restored.getDescription());
        assertEquals(List.of(new Monitor(ALARM, ALARM_ROLE)), restored.getMonitors());
    }

    @Test
    void invalidMonitorsAreRefusedBeforeTheEnvironmentChanges() {
        String appId = create(APPLICATION, props("Name", "orders")).getPhysicalId();
        StackResource env = create(ENVIRONMENT, props("ApplicationId", appId, "Name", "beta"));

        AwsException failure = assertThrows(AwsException.class, () -> update(env, props("ApplicationId", appId,
                "Name", "gamma", "Monitors", List.of(Map.of("AlarmArn", ALARM, "AlarmRoleArn", "not-a-role")))));

        assertEquals("BadRequestException", failure.getErrorCode());
        assertEquals("beta", service.getEnvironment(appId, env.getPhysicalId()).getName());
    }

    @Test
    void movingAnEnvironmentToAnotherApplicationReplacesItAndTheCommittedUpdateDeletesTheOldOne() {
        String first = create(APPLICATION, props("Name", "orders")).getPhysicalId();
        String second = create(APPLICATION, props("Name", "billing")).getPhysicalId();
        StackResource env = create(ENVIRONMENT, props("ApplicationId", first, "Name", "beta"));
        String oldId = env.getPhysicalId();

        update(env, props("ApplicationId", second, "Name", "beta"));

        String newId = env.getPhysicalId();
        assertNotEquals(oldId, newId);
        assertEquals(second, service.getEnvironment(second, newId).getApplicationId());
        assertTrue(provisioner.hasReplacementUpdate(env));
        assertEquals(oldId, provisioner.updateCleanupPhysicalId(env));
        assertTrue(service.findEnvironment(oldId).isPresent(), "the displaced environment stays until the update commits");

        assertTrue(provisioner.completeUpdate(env).complete());
        assertTrue(service.findEnvironment(oldId).isEmpty());
        provisioner.clearUpdate(env);
        assertFalse(provisioner.hasReplacementUpdate(env));
    }

    @Test
    void aRolledBackReplacementDeletesTheNewProfileAndNamesThePriorOneAgain() {
        String appId = create(APPLICATION, props("Name", "orders")).getPhysicalId();
        StackResource profile = create(PROFILE, props("ApplicationId", appId, "Name", "settings",
                "LocationUri", "hosted", "Type", "AWS.Freeform"));
        String oldId = profile.getPhysicalId();
        Map<String, String> before = Map.copyOf(profile.getAttributes());

        update(profile, props("ApplicationId", appId, "Name", "settings", "LocationUri", "ssm-parameter://settings",
                "Type", "AWS.Freeform"));
        String newId = profile.getPhysicalId();
        assertNotEquals(oldId, newId);
        assertEquals("ssm-parameter://settings", service.getConfigurationProfile(appId, newId).getLocationUri());

        assertTrue(provisioner.rollbackUpdate(profile));

        assertEquals(oldId, profile.getPhysicalId());
        assertEquals(before, profile.getAttributes(), "the prior profile's ID and create-only values are back");
        assertTrue(service.findConfigurationProfile(newId).isEmpty(), "the replacement is deleted");
        assertEquals("hosted", service.getConfigurationProfile(appId, oldId).getLocationUri());
    }

    @Test
    void aProfileWithoutTypeHasNoneAndAddingOneReplacesIt() {
        String appId = create(APPLICATION, props("Name", "orders")).getPhysicalId();
        StackResource profile = create(PROFILE, props("ApplicationId", appId, "Name", "settings",
                "LocationUri", "hosted"));
        String oldId = profile.getPhysicalId();
        assertEquals(oldId, profile.getAttributes().get("ConfigurationProfileId"));
        assertNull(service.getConfigurationProfile(appId, oldId).getType());

        update(profile, props("ApplicationId", appId, "Name", "flags", "LocationUri", "hosted",
                "Type", "AWS.AppConfig.FeatureFlags"));

        assertNotEquals(oldId, profile.getPhysicalId());
        ConfigurationProfile replacement = service.getConfigurationProfile(appId, profile.getPhysicalId());
        assertEquals("AWS.AppConfig.FeatureFlags", replacement.getType());
        assertEquals("flags", replacement.getName());
    }

    @Test
    void aProfileNameAndDescriptionChangeInPlace() {
        String appId = create(APPLICATION, props("Name", "orders")).getPhysicalId();
        StackResource profile = create(PROFILE, props("ApplicationId", appId, "Name", "settings",
                "LocationUri", "hosted", "Description", "First"));
        String id = profile.getPhysicalId();

        update(profile, props("ApplicationId", appId, "Name", "renamed", "LocationUri", "hosted"));

        assertEquals(id, profile.getPhysicalId());
        assertFalse(provisioner.hasReplacementUpdate(profile));
        ConfigurationProfile updated = service.getConfigurationProfile(appId, id);
        assertEquals("renamed", updated.getName());
        assertNull(updated.getDescription());
        provisioner.rollbackUpdate(profile);
        assertEquals("settings", service.getConfigurationProfile(appId, id).getName());
        assertEquals("First", service.getConfigurationProfile(appId, id).getDescription());
    }

    @Test
    void aStrategyChangesInPlaceAndPropertiesTheTemplateDropsGoBackToTheirDefaults() {
        StackResource strategy = create(STRATEGY, props("Name", "gradual", "ReplicateTo", "NONE",
                "DeploymentDurationInMinutes", 10, "FinalBakeTimeInMinutes", 5, "GrowthFactor", 25,
                "GrowthType", "EXPONENTIAL", "Description", "Slow"));
        String id = strategy.getPhysicalId();
        assertEquals(id, strategy.getAttributes().get("Id"));

        update(strategy, props("Name", "gradual", "ReplicateTo", "NONE", "DeploymentDurationInMinutes", "20",
                "GrowthFactor", 12.5));

        assertEquals(id, strategy.getPhysicalId());
        DeploymentStrategy updated = service.getDeploymentStrategy(id);
        assertEquals(20, updated.getDeploymentDurationInMinutes(), "a parameter supplies a number as a string");
        assertEquals(0, updated.getFinalBakeTimeInMinutes());
        assertEquals(12.5f, updated.getGrowthFactor());
        assertEquals("LINEAR", updated.getGrowthType());
        assertNull(updated.getDescription());

        provisioner.rollbackUpdate(strategy);
        DeploymentStrategy restored = service.getDeploymentStrategy(id);
        assertEquals(10, restored.getDeploymentDurationInMinutes());
        assertEquals(5, restored.getFinalBakeTimeInMinutes());
        assertEquals(25f, restored.getGrowthFactor());
        assertEquals("EXPONENTIAL", restored.getGrowthType());
        assertEquals("Slow", restored.getDescription());
    }

    @Test
    void renamingAStrategyReplacesIt() {
        StackResource strategy = create(STRATEGY, props("Name", "gradual", "ReplicateTo", "NONE",
                "DeploymentDurationInMinutes", 0, "GrowthFactor", 100));
        String oldId = strategy.getPhysicalId();

        update(strategy, props("Name", "immediate", "ReplicateTo", "NONE", "DeploymentDurationInMinutes", 0,
                "GrowthFactor", 100));

        assertNotEquals(oldId, strategy.getPhysicalId());
        assertEquals("immediate", service.getDeploymentStrategy(strategy.getPhysicalId()).getName());
        assertEquals(oldId, provisioner.updateCleanupPhysicalId(strategy));
        provisioner.completeUpdate(strategy);
        assertThrows(AwsException.class, () -> service.getDeploymentStrategy(oldId));
    }

    @Test
    void invalidStrategyNumbersAndEnumsAreRefusedBeforeAnythingIsCreated() {
        List<Map<String, Object>> invalid = List.of(
                props("Name", "s", "ReplicateTo", "NONE", "DeploymentDurationInMinutes", 1.5, "GrowthFactor", 10),
                props("Name", "s", "ReplicateTo", "NONE", "DeploymentDurationInMinutes", -1, "GrowthFactor", 10),
                props("Name", "s", "ReplicateTo", "NONE", "DeploymentDurationInMinutes", 1441, "GrowthFactor", 10),
                props("Name", "s", "ReplicateTo", "NONE", "DeploymentDurationInMinutes", "soon", "GrowthFactor", 10),
                props("Name", "s", "ReplicateTo", "NONE", "GrowthFactor", 10),
                props("Name", "s", "ReplicateTo", "NONE", "DeploymentDurationInMinutes", 1,
                        "FinalBakeTimeInMinutes", 2000, "GrowthFactor", 10),
                props("Name", "s", "ReplicateTo", "NONE", "DeploymentDurationInMinutes", 1, "GrowthFactor", 0.5),
                props("Name", "s", "ReplicateTo", "NONE", "DeploymentDurationInMinutes", 1, "GrowthFactor", 101),
                props("Name", "s", "ReplicateTo", "NONE", "DeploymentDurationInMinutes", 1),
                props("Name", "s", "ReplicateTo", "NONE", "DeploymentDurationInMinutes", 1, "GrowthFactor", 10,
                        "GrowthType", "QUADRATIC"),
                props("Name", "s", "ReplicateTo", "S3", "DeploymentDurationInMinutes", 1, "GrowthFactor", 10),
                props("Name", "s", "DeploymentDurationInMinutes", 1, "GrowthFactor", 10),
                props("ReplicateTo", "NONE", "DeploymentDurationInMinutes", 1, "GrowthFactor", 10),
                props("Name", "s".repeat(65), "ReplicateTo", "NONE", "DeploymentDurationInMinutes", 1,
                        "GrowthFactor", 10));
        for (Map<String, Object> properties : invalid) {
            AwsException failure = assertThrows(AwsException.class,
                    () -> provisioner.provision(resource(STRATEGY), MAPPER.valueToTree(properties), context(null)),
                    properties.toString());
            assertEquals("ValidationError", failure.getErrorCode(), properties.toString());
        }
        verify(service, never()).createDeploymentStrategy(any());
    }

    @Test
    void requiredPropertiesAndLimitsAreCheckedBeforeAnythingIsCreated() {
        String appId = create(APPLICATION, props("Name", "orders")).getPhysicalId();
        Map<String, Map<String, Object>> invalid = new LinkedHashMap<>();
        invalid.put(APPLICATION, props("Description", "no name"));
        invalid.put(APPLICATION + " long name", props("Name", "a".repeat(65)));
        invalid.put(APPLICATION + " long description", props("Name", "orders", "Description", "d".repeat(1025)));
        invalid.put(ENVIRONMENT, props("Name", "beta"));
        invalid.put(ENVIRONMENT + " no name", props("ApplicationId", appId));
        invalid.put(ENVIRONMENT + " deletion protection", props("ApplicationId", appId, "Name", "beta",
                "DeletionProtectionCheck", "SOMETIMES"));
        invalid.put(PROFILE, props("ApplicationId", appId, "Name", "settings"));
        invalid.put(PROFILE + " long name", props("ApplicationId", appId, "Name", "p".repeat(129),
                "LocationUri", "hosted"));
        invalid.put(PROFILE + " no application", props("Name", "settings", "LocationUri", "hosted"));
        for (Map.Entry<String, Map<String, Object>> entry : invalid.entrySet()) {
            String type = entry.getKey().split(" ")[0];
            AwsException failure = assertThrows(AwsException.class, () -> provisioner.provision(resource(type),
                    MAPPER.valueToTree(entry.getValue()), context(null)), entry.getKey());
            assertEquals("ValidationError", failure.getErrorCode(), entry.getKey());
        }
        assertEquals(1, service.listApplications().size());
        assertTrue(service.listEnvironments(appId).isEmpty());
        assertTrue(service.listConfigurationProfiles(appId).isEmpty());
    }

    @Test
    void noValueLeavesAnOptionalPropertyOut() {
        StackResource app = create(APPLICATION, props("Name", "orders",
                "Description", Map.of("Ref", "AWS::NoValue")));

        assertNull(service.getApplication(app.getPhysicalId()).getDescription());
    }

    @Test
    void aStubFromBeforeTheProvisionerIsReplacedByARealEntityAndARollbackPutsTheStubBack() {
        StackResource app = resource(APPLICATION);
        String stubId = "App-0a1b2c3d";
        app.setPhysicalId(stubId);
        app.getAttributes().put("Arn", CfnResourceDispatcher.STUB_ARN_PREFIX + "App");

        provisioner.provision(app, MAPPER.valueToTree(props("Name", "orders")), context(stubId));

        String created = app.getPhysicalId();
        assertNotEquals(stubId, created);
        assertEquals("orders", service.getApplication(created).getName());
        assertTrue(provisioner.rollbackUpdate(app));
        assertEquals(stubId, app.getPhysicalId());
        assertTrue(service.listApplications().isEmpty(), "the rollback deletes what the migration created");
    }

    @Test
    void deletesGoByExactIdAndCountAnEntityAlreadyGoneAsDeleted() {
        StackResource app = create(APPLICATION, props("Name", "orders"));
        StackResource env = create(ENVIRONMENT, props("ApplicationId", app.getPhysicalId(), "Name", "beta"));
        StackResource profile = create(PROFILE, props("ApplicationId", app.getPhysicalId(), "Name", "settings",
                "LocationUri", "hosted"));
        StackResource strategy = create(STRATEGY, props("Name", "now", "ReplicateTo", "NONE",
                "DeploymentDurationInMinutes", 0, "GrowthFactor", 100));
        // An application named like a gone application's ID must survive that ID's delete.
        Application namedLikeAnId = service.createApplication(Map.of("Name", "gone123"));

        for (StackResource resource : List.of(env, profile, strategy, app)) {
            provisioner.delete(resource, REGION);
            assertDoesNotThrow(() -> provisioner.delete(resource, REGION), resource.getResourceType());
        }
        provisioner.delete(APPLICATION, "gone123", REGION);

        assertTrue(service.findEnvironment(env.getPhysicalId()).isEmpty());
        assertTrue(service.findConfigurationProfile(profile.getPhysicalId()).isEmpty());
        assertThrows(AwsException.class, () -> service.getDeploymentStrategy(strategy.getPhysicalId()));
        assertEquals(List.of(namedLikeAnId.getId()), service.listApplications().stream().map(Application::getId).toList());
    }

    /**
     * DeleteApplication takes a name as well as an ID, so a delete that first looks the application
     * up and then deletes it through that API removes another application named like its ID when
     * the API deletes the first in between. A stack's delete is one exact-ID call; this pins that
     * it neither calls DeleteApplication's lookup nor looks the application up first.
     */
    @Test
    void anApplicationDeletedThroughTheApiMidDeleteLeavesOneNamedLikeItsIdAlone() {
        String appId = create(APPLICATION, props("Name", "orders")).getPhysicalId();
        Application namedLikeTheId = service.createApplication(Map.of("Name", appId));
        doAnswer(invocation -> {
            Object found = invocation.callRealMethod();
            service.deleteApplication(appId);
            return found;
        }).when(service).findApplication(appId);

        provisioner.delete(APPLICATION, appId, REGION);

        verify(service).deleteApplicationById(appId);
        verify(service, never()).deleteApplication(anyString());
        assertEquals(List.of(namedLikeTheId.getId()),
                service.listApplications().stream().map(Application::getId).toList());
    }

    @Test
    void aFailedCreateUndoesOnlyItsOwnApplication() {
        StackResource app = resource(APPLICATION);
        doAnswer(invocation -> {
            String appId = invocation.getArgument(0);
            service.deleteApplication(appId);
            service.createApplication(Map.of("Name", appId));
            throw new AwsException("InternalServerException", "tagging failed", 500);
        }).when(service).setApplicationTags(anyString(), any());

        assertThrows(AwsException.class, () -> provisioner.provision(app,
                MAPPER.valueToTree(props("Name", "orders", "Tags", List.of(tag("team", "payments")))), context(null)));

        assertEquals(List.of(app.getPhysicalId()),
                service.listApplications().stream().map(Application::getName).toList(),
                "the application named like the undone one's ID is not the stack's");
    }

    /**
     * Tags, like deletes, go by exact ID: an application the API deletes while the stack writes its
     * tags fails the stack, and the tags never land on another application named like its ID.
     */
    @Test
    void anUpdateWhoseApplicationTheApiDeletedLeavesOneNamedLikeItsIdUntagged() {
        StackResource app = create(APPLICATION, props("Name", "orders", "Tags", List.of(tag("team", "payments"))));
        String appId = app.getPhysicalId();
        Application namedLikeTheId = service.createApplication(Map.of("Name", appId));
        service.tagApplication(namedLikeTheId.getId(), Map.of("owner", "billing"));
        doAnswer(invocation -> {
            Object updated = invocation.callRealMethod();
            service.deleteApplication(appId);
            return updated;
        }).when(service).updateApplication(anyString(), any(), any());

        AwsException failure = assertThrows(AwsException.class, () -> update(app, props("Name", "orders-v2",
                "Tags", List.of(tag("team", "checkout")))));

        assertEquals("ResourceNotFoundException", failure.getErrorCode());
        assertEquals(Map.of("owner", "billing"), service.getApplication(namedLikeTheId.getId()).getTags());
    }

    @Test
    void aCreateWhoseApplicationTheApiDeletedLeavesOneNamedLikeItsIdUntagged() {
        doAnswer(invocation -> {
            Application created = (Application) invocation.callRealMethod();
            service.deleteApplication(created.getId());
            service.createApplication(Map.of("Name", created.getId()));
            return created;
        }).doCallRealMethod().when(service).createApplication(any());
        StackResource app = resource(APPLICATION);

        AwsException failure = assertThrows(AwsException.class, () -> provisioner.provision(app,
                MAPPER.valueToTree(props("Name", "orders", "Tags", List.of(tag("team", "payments")))), context(null)));

        assertEquals("ResourceNotFoundException", failure.getErrorCode());
        Application namedLikeTheId = service.getApplication(app.getPhysicalId());
        assertNotEquals(app.getPhysicalId(), namedLikeTheId.getId());
        assertTrue(namedLikeTheId.getTags().isEmpty(), "the stack's tags are not written to another application");
    }

    /**
     * {@code ApplicationId} is the application's ID or name, as on AWS: an environment and a profile
     * whose {@code ApplicationId} names an existing application are created under it.
     */
    @Test
    void anEnvironmentAndAProfileCanNameTheirApplication() {
        String appId = create(APPLICATION, props("Name", "orders")).getPhysicalId();

        StackResource env = create(ENVIRONMENT, props("ApplicationId", "orders", "Name", "beta"));
        StackResource profile = create(PROFILE, props("ApplicationId", "orders", "Name", "settings",
                "LocationUri", "hosted"));

        assertEquals(appId, service.findEnvironment(env.getPhysicalId()).orElseThrow().getApplicationId());
        assertEquals(appId, service.findConfigurationProfile(profile.getPhysicalId()).orElseThrow().getApplicationId());
        String recordedApplication = CfnRollback.USES_ATTR_PREFIX + "AppConfigApplicationId";
        assertEquals(appId, env.getAttributes().get(recordedApplication), "the stack records the application's ID");
        assertEquals(appId, profile.getAttributes().get(recordedApplication));
    }

    @Test
    void anUpdateThatDropsTagsClearsThem() {
        StackResource app = create(APPLICATION, props("Name", "orders", "Tags", List.of(tag("team", "payments"))));

        update(app, props("Name", "orders"));

        assertTrue(service.getApplication(app.getPhysicalId()).getTags().isEmpty());
    }

    /**
     * An update that moves an environment and a profile to another application can delete the old
     * application first, so their deletes must not depend on it; they still take their deployments
     * and hosted configuration versions with them.
     */
    @Test
    void anEnvironmentAndAProfileWhoseApplicationIsGoneAreStillDeleted() {
        String appId = create(APPLICATION, props("Name", "orders")).getPhysicalId();
        StackResource env = create(ENVIRONMENT, props("ApplicationId", appId, "Name", "beta"));
        StackResource profile = create(PROFILE, props("ApplicationId", appId, "Name", "settings",
                "LocationUri", "hosted"));
        service.createHostedConfigurationVersion(appId, profile.getPhysicalId(), "{}".getBytes(), "application/json",
                null);
        service.startDeployment(appId, env.getPhysicalId(), Map.of("ConfigurationProfileId", profile.getPhysicalId(),
                "ConfigurationVersion", "1", "DeploymentStrategyId", "AppConfig.AllAtOnce"));
        service.deleteApplication(appId);

        provisioner.delete(ENVIRONMENT, env.getPhysicalId(), REGION);
        provisioner.delete(PROFILE, profile.getPhysicalId(), REGION);

        assertTrue(service.findEnvironment(env.getPhysicalId()).isEmpty());
        assertTrue(service.findConfigurationProfile(profile.getPhysicalId()).isEmpty());
        assertTrue(storage.create("appconfig", "appconfig-deployments.json",
                new TypeReference<Map<String, Deployment>>() {}).scan(key -> key.startsWith(appId + "::")).isEmpty());
        assertTrue(storage.create("appconfig", "appconfig-versions.json",
                new TypeReference<Map<String, HostedConfigurationVersion>>() {})
                .scan(key -> key.startsWith(appId + "::")).isEmpty());
    }

    @Test
    void aDeleteFailureOtherThanNotFoundPropagates() {
        String appId = create(APPLICATION, props("Name", "orders")).getPhysicalId();
        StackResource env = create(ENVIRONMENT, props("ApplicationId", appId, "Name", "beta"));
        AwsException storage = new AwsException("InternalServerException", "storage failed", 500);
        doThrow(storage).when(service).deleteEnvironmentById(env.getPhysicalId());

        assertSame(storage, assertThrows(AwsException.class, () -> provisioner.delete(env, REGION)));
    }

    @Test
    void aStubIsNotDeleted() {
        StackResource stub = resource(ENVIRONMENT);
        stub.setPhysicalId("Env-0a1b2c3d");
        stub.getAttributes().put("Arn", CfnResourceDispatcher.STUB_ARN_PREFIX + "Env");

        provisioner.delete(stub, REGION);

        verify(service, never()).deleteEnvironmentById(anyString());
        verify(service, never()).deleteEnvironment(anyString(), anyString());
    }

    @Test
    void anUpdateOfAnEnvironmentDeletedOutsideTheStackFails() {
        String appId = create(APPLICATION, props("Name", "orders")).getPhysicalId();
        StackResource env = create(ENVIRONMENT, props("ApplicationId", appId, "Name", "beta"));
        service.deleteEnvironment(appId, env.getPhysicalId());

        AwsException failure = assertThrows(AwsException.class,
                () -> update(env, props("ApplicationId", appId, "Name", "gamma")));

        assertEquals("ResourceNotFoundException", failure.getErrorCode());
    }

    private StackResource create(String type, Map<String, Object> properties) {
        StackResource resource = resource(type);
        provisioner.provision(resource, MAPPER.valueToTree(properties), context(null));
        return resource;
    }

    /** Provisions {@code resource} again with {@code properties}, as an UpdateStack does. */
    private void update(StackResource resource, Map<String, Object> properties) {
        provisioner.provision(resource, MAPPER.valueToTree(properties), context(resource.getPhysicalId()));
    }

    /** The resource as the stack stores it, so a provision of the copy leaves the original alone. */
    private static StackResource copy(StackResource resource) {
        StackResource copy = resource(resource.getResourceType());
        copy.setPhysicalId(resource.getPhysicalId());
        copy.getAttributes().putAll(resource.getAttributes());
        return copy;
    }

    private static StackResource resource(String type) {
        StackResource resource = new StackResource();
        resource.setLogicalId(type.substring(type.lastIndexOf(':') + 1));
        resource.setResourceType(type);
        return resource;
    }

    private ProvisionContext context(String priorPhysicalId) {
        return new ProvisionContext(engine, REGION, ACCOUNT, STACK, priorPhysicalId);
    }

    /** Alternating property names and values, in order. */
    private static Map<String, Object> props(Object... namesAndValues) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            properties.put((String) namesAndValues[i], namesAndValues[i + 1]);
        }
        return properties;
    }

    private static Map<String, String> tag(String key, String value) {
        return Map.of("Key", key, "Value", value);
    }
}
