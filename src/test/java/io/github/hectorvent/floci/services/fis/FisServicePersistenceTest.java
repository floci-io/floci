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
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
                  "targets":{},
                  "actions":{"wait":{"actionId":"aws:fis:wait","parameters":{"duration":"PT1M"}}},
                  "tags":{"environment":"persistence"}
                }
                """));
        String templateId = createdTemplate.path("experimentTemplate").path("id").asText();
        String templateArn = createdTemplate.path("experimentTemplate").path("arn").asText();

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

        FisService restarted = newService(directory);

        assertEquals(templateId,
                restarted.getExperimentTemplate(REGION, templateId)
                        .path("experimentTemplate").path("id").asText());
        assertEquals("persistence",
                restarted.listTags(REGION, templateArn).get("environment"));
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
        Path file = directory.resolve("fis-state.json");

        StorageBackend<String, ObjectNode> firstStore =
                load(file, requestContextInstance, ACCOUNT_ID);
        FisService firstAccountA = newService(firstStore, ACCOUNT_A);
        FisService firstAccountB = newService(firstStore, ACCOUNT_B);
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

        StorageBackend<String, ObjectNode> restartedStore =
                load(file, requestContextInstance, ACCOUNT_ID);
        FisService restartedAccountA = newService(restartedStore, ACCOUNT_A);
        FisService restartedAccountB = newService(restartedStore, ACCOUNT_B);

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
        StorageBackend<String, ObjectNode> store =
                load(directory.resolve("fis-state.json"), null, ACCOUNT_ID);
        return newService(store, ACCOUNT_ID);
    }

    private FisService newService(StorageBackend<String, ObjectNode> store, String accountId) {
        ObjectMapper mapper = new ObjectMapper();
        RegionResolver regionResolver = new RegionResolver(REGION, accountId);
        FisCatalog catalog = new FisCatalog(mapper, regionResolver);
        return new FisService(store, mapper, regionResolver, catalog);
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

    private StorageBackend<String, ObjectNode> load(
            Path file, Instance<RequestContext> requestContextInstance, String defaultAccountId) {
        PersistentStorage<String, ObjectNode> backend = new PersistentStorage<>(
                file, new TypeReference<Map<String, ObjectNode>>() {});
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
