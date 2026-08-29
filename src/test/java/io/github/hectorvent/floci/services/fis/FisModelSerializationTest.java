package io.github.hectorvent.floci.services.fis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.fis.model.Experiment;
import io.github.hectorvent.floci.services.fis.model.ExperimentTemplate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class FisModelSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void experimentTemplateRoundTripsAwsMemberNamesAndNestedTypes() throws Exception {
        JsonNode expected = mapper.readTree("""
                {
                  "id":"EXT123",
                  "arn":"arn:aws:fis:us-east-1:000000000000:experiment-template/EXT123",
                  "description":"typed template",
                  "targets":{"instances":{
                    "resourceType":"aws:ec2:instance",
                    "resourceArns":["arn:aws:ec2:us-east-1:000000000000:instance/i-123"],
                    "resourceTags":{"environment":"test"},
                    "filters":[{"path":"State.Name","values":["running"]}],
                    "selectionMode":"ALL",
                    "parameters":{"availabilityZoneIdentifier":"us-east-1a"}
                  }},
                  "actions":{"stop":{
                    "actionId":"aws:ec2:stop-instances",
                    "description":"stop one instance",
                    "parameters":{"startInstancesAfterDuration":"PT1M"},
                    "targets":{"Instances":"instances"},
                    "startAfter":["wait"]
                  }},
                  "stopConditions":[{"source":"none"}],
                  "creationTime":1.25,
                  "lastUpdateTime":2.5,
                  "roleArn":"arn:aws:iam::000000000000:role/fis-role",
                  "tags":{"owner":"test"},
                  "experimentOptions":{
                    "accountTargeting":"single-account",
                    "emptyTargetResolutionMode":"fail"
                  },
                  "targetAccountConfigurationsCount":0
                }
                """);

        ExperimentTemplate model = mapper.treeToValue(expected, ExperimentTemplate.class);
        JsonNode actual = mapper.valueToTree(model);

        assertEquals(mapper.writeValueAsString(expected), mapper.writeValueAsString(actual));
        assertInstanceOf(
                io.github.hectorvent.floci.services.fis.model.ExperimentTemplateTarget.class,
                model.getTargets().get("instances"));
        assertFalse(actual.has("logConfiguration"));
        assertFalse(actual.has("experimentReportConfiguration"));
    }

    @Test
    void experimentRoundTripsRuntimeStateWithoutNullOptionalMembers() throws Exception {
        JsonNode expected = mapper.readTree("""
                {
                  "id":"EXP123",
                  "arn":"arn:aws:fis:us-east-1:000000000000:experiment/EXP123",
                  "experimentTemplateId":"EXT123",
                  "roleArn":"arn:aws:iam::000000000000:role/fis-role",
                  "state":{"status":"running","reason":"safe emulation"},
                  "targets":{},
                  "actions":{"wait":{
                    "actionId":"aws:fis:wait",
                    "parameters":{"duration":"PT1M"},
                    "state":{"status":"running","reason":"safe emulation"},
                    "startTime":3.75
                  }},
                  "stopConditions":[{"source":"none"}],
                  "creationTime":3.5,
                  "startTime":3.75,
                  "tags":{},
                  "experimentOptions":{
                    "accountTargeting":"single-account",
                    "emptyTargetResolutionMode":"fail",
                    "actionsMode":"run-all"
                  },
                  "targetAccountConfigurationsCount":0
                }
                """);

        Experiment model = mapper.treeToValue(expected, Experiment.class);
        JsonNode actual = mapper.valueToTree(model);

        assertEquals(mapper.writeValueAsString(expected), mapper.writeValueAsString(actual));
        assertFalse(actual.has("endTime"));
        assertFalse(actual.path("state").has("error"));
        assertFalse(actual.has("experimentReport"));
    }
}
