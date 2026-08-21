package io.github.hectorvent.floci.services.fis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.fis.model.Experiment;
import io.github.hectorvent.floci.services.fis.model.ExperimentTemplate;
import io.github.hectorvent.floci.services.fis.model.IdempotencyRecord;
import io.github.hectorvent.floci.services.fis.model.ResolvedTarget;
import io.github.hectorvent.floci.services.fis.model.SafetyLever;
import io.github.hectorvent.floci.services.fis.model.TargetAccountConfiguration;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FisServicePersistenceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_ID = "000000000000";
    private static final String ACCOUNT_A = "111111111111";
    private static final String ACCOUNT_B = "222222222222";

    @Test
    void templatesExperimentsAndSafetyLeverSurviveRestart(@TempDir Path directory) throws Exception {
        FisService first = newService(directory);
        ObjectMapper mapper = new ObjectMapper();

        ObjectNode createdTemplate = first.createExperimentTemplate(REGION, mapper.readTree("""
                {
                  "clientToken":"persistence-template-token",
                  "description":"persistent FIS template",
                  "roleArn":"arn:aws:iam::000000000000:role/fis-role",
                  "stopConditions":[{"source":"none"}],
                  "targets":{"instances":{
                    "resourceType":"aws:ec2:instance",
                    "resourceArns":["arn:aws:ec2:us-east-1:000000000000:instance/i-00000000000000001"],
                    "selectionMode":"ALL"
                  }},
                  "actions":{"stop":{
                    "actionId":"aws:ec2:stop-instances",
                    "targets":{"Instances":"instances"}
                  }},
                  "tags":{"environment":"persistence"},
                  "experimentOptions":{"accountTargeting":"multi-account"}
                }
                """));
        String templateId = createdTemplate.path("experimentTemplate").path("id").asText();
        String templateArn = createdTemplate.path("experimentTemplate").path("arn").asText();
        String targetAccountId = "111111111111";
        first.createTargetAccountConfiguration(REGION, templateId, targetAccountId, mapper.readTree("""
                {
                  "roleArn":"arn:aws:iam::111111111111:role/fis-target-role",
                  "description":"persistent target account"
                }
                """));
        String actionArn = first.getAction(REGION, "aws:ec2:stop-instances")
                .path("action").path("arn").asText();
        first.tagResource(REGION, actionArn, Map.of("owner", "persistence-test"));

        ObjectNode startedExperiment = first.startExperiment(REGION, mapper.readTree("""
                {
                  "clientToken":"persistence-experiment-token",
                  "experimentTemplateId":"%s",
                  "experimentOptions":{"actionsMode":"skip-all"},
                  "tags":{"purpose":"restart-test"}
                }
                """.formatted(templateId)));
        String experimentId = startedExperiment.path("experiment").path("id").asText();

        first.updateSafetyLeverState(REGION, "default", mapper.readTree("""
                {"state":{"status":"engaged","reason":"persistence test"}}
                """));

        FisStores restartedStores = load(directory, null, ACCOUNT_ID);
        FisService restarted = newService(restartedStores, ACCOUNT_ID);

        assertInstanceOf(ExperimentTemplate.class, restartedStores.templates.scan(key -> true).get(0));
        assertInstanceOf(Experiment.class, restartedStores.experiments.scan(key -> true).get(0));
        assertInstanceOf(
                TargetAccountConfiguration.class, restartedStores.targetAccounts.scan(key -> true).get(0));
        assertInstanceOf(ResolvedTarget.class, restartedStores.resolvedTargets.scan(key -> true).get(0));
        assertInstanceOf(SafetyLever.class, restartedStores.safetyLevers.scan(key -> true).get(0));
        assertInstanceOf(IdempotencyRecord.class, restartedStores.idempotency.scan(key -> true).get(0));

        assertEquals(templateId,
                restarted.getExperimentTemplate(REGION, templateId)
                        .path("experimentTemplate").path("id").asText());
        assertEquals("persistence",
                restarted.listTags(REGION, templateArn).get("environment"));
        assertEquals("persistent target account",
                restarted.getTargetAccountConfiguration(REGION, templateId, targetAccountId)
                        .path("targetAccountConfiguration").path("description").asText());
        assertEquals(targetAccountId,
                restarted.getExperimentTargetAccountConfiguration(REGION, experimentId, targetAccountId)
                        .path("targetAccountConfiguration").path("accountId").asText());
        assertEquals(1, restarted.listExperimentResolvedTargets(
                REGION, experimentId, null, null, null).path("resolvedTargets").size());
        assertEquals("persistence-test", restarted.listTags(REGION, actionArn).get("owner"));
        assertEquals("completed",
                restarted.getExperiment(REGION, experimentId)
                        .path("experiment").path("state").path("status").asText());
        assertEquals("engaged",
                restarted.getSafetyLever(REGION, "default")
                        .path("safetyLever").path("state").path("status").asText());
    }

    @Test
    void templatesTagsAndIdempotencyRemainAccountIsolatedAcrossRestart(
            @TempDir Path directory) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        RequestContext requestContext = new RequestContext();
        requestContext.setRegion(REGION);
        Instance<RequestContext> requestContextInstance = requestContextInstance(requestContext);
        FisStores firstStores = load(directory, requestContextInstance, ACCOUNT_ID);
        FisService firstAccountA = newService(firstStores, ACCOUNT_A);
        FisService firstAccountB = newService(firstStores, ACCOUNT_B);
        ObjectNode accountARequest = templateRequest(mapper, ACCOUNT_A, "account-a-template");
        ObjectNode accountBRequest = templateRequest(mapper, ACCOUNT_B, "account-b-template");

        requestContext.setAccountId(ACCOUNT_A);
        ObjectNode accountACreated = firstAccountA.createExperimentTemplate(REGION, accountARequest);
        String accountATemplateId = accountACreated.path("experimentTemplate").path("id").asText();
        String accountATemplateArn = accountACreated.path("experimentTemplate").path("arn").asText();
        firstAccountA.tagResource(REGION, accountATemplateArn, Map.of("owner", "account-a"));

        requestContext.setAccountId(ACCOUNT_B);
        ObjectNode accountBCreated = firstAccountB.createExperimentTemplate(REGION, accountBRequest);
        String accountBTemplateId = accountBCreated.path("experimentTemplate").path("id").asText();
        String accountBTemplateArn = accountBCreated.path("experimentTemplate").path("arn").asText();
        firstAccountB.tagResource(REGION, accountBTemplateArn, Map.of("owner", "account-b"));

        assertNotEquals(accountATemplateId, accountBTemplateId);

        FisStores restartedStores = load(directory, requestContextInstance, ACCOUNT_ID);
        FisService restartedAccountA = newService(restartedStores, ACCOUNT_A);
        FisService restartedAccountB = newService(restartedStores, ACCOUNT_B);

        requestContext.setAccountId(ACCOUNT_A);
        assertEquals(accountATemplateId,
                restartedAccountA.createExperimentTemplate(REGION, accountARequest)
                        .path("experimentTemplate").path("id").asText());
        assertEquals("account-a", restartedAccountA.listTags(REGION, accountATemplateArn).get("owner"));
        assertEquals(1, restartedAccountA.listExperimentTemplates(REGION, null, null)
                .path("experimentTemplates").size());
        assertThrows(AwsException.class,
                () -> restartedAccountA.getExperimentTemplate(REGION, accountBTemplateId));

        requestContext.setAccountId(ACCOUNT_B);
        assertEquals(accountBTemplateId,
                restartedAccountB.createExperimentTemplate(REGION, accountBRequest)
                        .path("experimentTemplate").path("id").asText());
        assertEquals("account-b", restartedAccountB.listTags(REGION, accountBTemplateArn).get("owner"));
        assertEquals(1, restartedAccountB.listExperimentTemplates(REGION, null, null)
                .path("experimentTemplates").size());
        assertThrows(AwsException.class,
                () -> restartedAccountB.getExperimentTemplate(REGION, accountATemplateId));
    }

    private FisService newService(Path directory) {
        return newService(load(directory, null, ACCOUNT_ID), ACCOUNT_ID);
    }

    private FisService newService(FisStores stores, String accountId) {
        ObjectMapper mapper = new ObjectMapper();
        RegionResolver regionResolver = new RegionResolver(REGION, accountId);
        FisCatalog catalog = new FisCatalog(mapper, regionResolver);
        return new FisService(stores, mapper, regionResolver, catalog);
    }

    private ObjectNode templateRequest(ObjectMapper mapper, String accountId, String description)
            throws Exception {
        return (ObjectNode) mapper.readTree("""
                {
                  "clientToken":"shared-account-token",
                  "description":"%s",
                  "roleArn":"arn:aws:iam::%s:role/fis-role",
                  "stopConditions":[{"source":"none"}],
                  "targets":{},
                  "actions":{"wait":{"actionId":"aws:fis:wait","parameters":{"duration":"PT1M"}}},
                  "tags":{"createdBy":"persistence-test"}
                }
                """.formatted(description, accountId));
    }

    private FisStores load(
            Path directory, Instance<RequestContext> requestContextInstance, String defaultAccountId) {
        return new FisStores(
                load(directory.resolve("fis-experiment-templates.json"),
                        new TypeReference<Map<String, ExperimentTemplate>>() {},
                        requestContextInstance, defaultAccountId),
                load(directory.resolve("fis-experiments.json"),
                        new TypeReference<Map<String, Experiment>>() {},
                        requestContextInstance, defaultAccountId),
                load(directory.resolve("fis-target-account-configurations.json"),
                        new TypeReference<Map<String, TargetAccountConfiguration>>() {},
                        requestContextInstance, defaultAccountId),
                load(directory.resolve("fis-resolved-targets.json"),
                        new TypeReference<Map<String, ResolvedTarget>>() {},
                        requestContextInstance, defaultAccountId),
                load(directory.resolve("fis-safety-levers.json"),
                        new TypeReference<Map<String, SafetyLever>>() {},
                        requestContextInstance, defaultAccountId),
                load(directory.resolve("fis-action-tags.json"),
                        new TypeReference<Map<String, Map<String, String>>>() {},
                        requestContextInstance, defaultAccountId),
                load(directory.resolve("fis-idempotency.json"),
                        new TypeReference<Map<String, IdempotencyRecord>>() {},
                        requestContextInstance, defaultAccountId));
    }

    private <T> StorageBackend<String, T> load(
            Path file, TypeReference<Map<String, T>> typeReference,
            Instance<RequestContext> requestContextInstance, String defaultAccountId) {
        PersistentStorage<String, T> backend = new PersistentStorage<>(file, typeReference);
        backend.load();
        return new AccountAwareStorageBackend<>(backend, requestContextInstance, defaultAccountId);
    }

    @SuppressWarnings("unchecked")
    private Instance<RequestContext> requestContextInstance(RequestContext requestContext) {
        return (Instance<RequestContext>) Proxy.newProxyInstance(
                Instance.class.getClassLoader(), new Class<?>[] { Instance.class },
                (proxy, method, args) -> {
                    if ("get".equals(method.getName())) {
                        return requestContext;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
