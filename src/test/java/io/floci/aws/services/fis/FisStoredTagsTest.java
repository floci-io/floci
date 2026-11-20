package io.floci.aws.services.fis;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.fis.model.ExperimentTemplate;
import io.floci.aws.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** IAM's lookup of the tags AWS FIS holds on an experiment template, an experiment or an action. */
class FisStoredTagsTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * The lookup reads the tags of the template, the experiment or the action the ARN names, as the tag calls leave
     * them, and nothing for another account's, another Region's, an unknown resource's or another service's ARN.
     */
    @Test
    void storedTagsAreTheTagsOfTheResourceTheArnNames() throws Exception {
        FisService service = service(new InMemoryStorageFactory(ACCOUNT));
        String templateId = service.createExperimentTemplate(REGION, mapper.readTree("""
                {"clientToken":"stored-tags-template","description":"d",
                 "roleArn":"arn:aws:iam::000000000000:role/fis-role","stopConditions":[{"source":"none"}],
                 "targets":{},"actions":{"wait":{"actionId":"aws:fis:wait","parameters":{"duration":"PT1M"}}},
                 "tags":{"team":"web","env":"dev"}}
                """)).path("experimentTemplate").path("id").asText();
        String templateArn = service.getExperimentTemplate(REGION, templateId).path("experimentTemplate").path("arn")
                .asText();
        service.tagResource(REGION, templateArn, Map.of("team", "data"));
        service.untagResource(REGION, templateArn, List.of("env"));
        String experimentArn = service.startExperiment(REGION, mapper.readTree("""
                {"clientToken":"stored-tags-experiment","experimentTemplateId":"%s",
                 "experimentOptions":{"actionsMode":"skip-all"},"tags":{"team":"ops"}}
                """.formatted(templateId))).path("experiment").path("arn").asText();
        String actionArn = service.getAction(REGION, "aws:fis:wait").path("action").path("arn").asText();
        service.tagResource(REGION, actionArn, Map.of("team", "sre"));

        assertEquals("fis", service.namespace());
        assertEquals(Map.of("team", "data"), service.storedTags(templateArn));
        assertEquals(Map.of("team", "ops"), service.storedTags(experimentArn));
        assertEquals(Map.of("team", "sre"), service.storedTags(actionArn));
        assertTrue(service.storedTags(templateArn.replace(ACCOUNT, "111122223333")).isEmpty());
        assertTrue(service.storedTags(templateArn.replace(REGION, "us-west-2")).isEmpty());
        assertTrue(service.storedTags(templateArn.replace(templateId, "EXTmissing")).isEmpty());
        assertTrue(service.storedTags(actionArn.replace("aws:fis:wait", "aws:fis:inject-api-throttle-error"))
                .isEmpty());
        assertTrue(service.storedTags("arn:aws:ec2:us-east-1:000000000000:instance/i-0123456789abcdef0").isEmpty());
    }

    /** A template stored before storage was account-aware is read in place: the lookup migrates nothing. */
    @Test
    void storedTagsReadAPreAccountAwareTemplateInPlace() {
        InMemoryStorage<String, ExperimentTemplate> raw = new InMemoryStorage<>();
        FisService service =
                service(new InMemoryStorageFactory(ACCOUNT).withStore("fis-experiment-templates.json", raw));
        ExperimentTemplate legacy = new ExperimentTemplate();
        legacy.setId("EXTlegacy");
        legacy.setArn("arn:aws:fis:us-east-1:000000000000:experiment-template/EXTlegacy");
        legacy.setTags(Map.of("team", "data"));
        raw.put(REGION + "::template::EXTlegacy", legacy);

        assertEquals(Map.of("team", "data"), service.storedTags(legacy.getArn()));
        assertEquals(Set.of(REGION + "::template::EXTlegacy"), raw.keys());
    }

    private FisService service(InMemoryStorageFactory storage) {
        RegionResolver regionResolver = new RegionResolver(REGION, ACCOUNT);
        return new FisService(new FisStores(storage), mapper, regionResolver, new FisCatalog(mapper, regionResolver));
    }
}
