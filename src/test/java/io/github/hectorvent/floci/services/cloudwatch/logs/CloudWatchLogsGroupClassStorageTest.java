package io.github.hectorvent.floci.services.cloudwatch.logs;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.LogGroup;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CloudWatchLogsGroupClassStorageTest {

    @ParameterizedTest
    @CsvSource(value = {"OMITTED,STANDARD", "STANDARD,STANDARD", "INFREQUENT_ACCESS,INFREQUENT_ACCESS",
            "DELIVERY,DELIVERY"}, nullValues = "OMITTED")
    void storedGroupsRetainTheirClassAndLegacyGroupsDefaultToStandard(String storedClass, String expectedClass)
            throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String json = storedClass == null ? "{\"logGroupName\":\"legacy\"}"
                : "{\"logGroupName\":\"group\",\"logGroupClass\":\"" + storedClass + "\"}";
        LogGroup reloaded = mapper.readValue(json, LogGroup.class);
        assertEquals(expectedClass, mapper.readTree(mapper.writeValueAsString(reloaded)).path("logGroupClass").asText());
    }
}
