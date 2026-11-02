package io.github.hectorvent.floci.services.cloudtrail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudtrail.model.AdvancedEventSelector;
import io.github.hectorvent.floci.services.cloudtrail.model.AdvancedFieldSelector;
import io.github.hectorvent.floci.services.cloudtrail.model.EventSelector;
import io.github.hectorvent.floci.services.iam.IamService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CloudTrailManagementEventTest {
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "661100000002";
    private static final String SOURCE = "secretsmanager.amazonaws.com";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private CloudTrailService service;

    @BeforeEach
    void setUp() {
        StorageFactory factory = mock(StorageFactory.class);
        when(factory.create(anyString(), anyString(), any())).thenReturn(AccountAwareStorageBackend.inMemory(ACCOUNT));
        service = new CloudTrailService(factory, new RegionResolver(REGION, ACCOUNT), mock(IamService.class), MAPPER);
    }

    private void trail(boolean multiRegion) {
        service.createTrail(REGION, "management-trail", "example-audit-logs", null, null,
                false, multiRegion, false, false);
    }

    private CloudTrailService.ManagementEventInput event(String region) {
        return new CloudTrailService.ManagementEventInput(SOURCE, "PutSecretValue", region, "test",
                MAPPER.createObjectNode().put("secretId", "example"),
                MAPPER.createObjectNode().put("arn", "arn:aws:secretsmanager:" + region + ":" + ACCOUNT + ":secret:example"));
    }

    @Test
    void loggingIsRequiredAndTheDefaultSelectorIncludesManagementWrites() {
        assertTrue(service.emitManagementEvent(event(REGION)).isEmpty());
        trail(false);
        assertTrue(service.emitManagementEvent(event(REGION)).isEmpty());
        service.startLogging(REGION, "management-trail");
        ObjectNode record = service.emitManagementEvent(event(REGION)).orElseThrow();
        assertEquals("Management", record.path("eventCategory").asText());
        assertEquals("AwsApiCall", record.path("eventType").asText());
        assertEquals(ACCOUNT, record.path("recipientAccountId").asText());
        assertTrue(record.path("managementEvent").asBoolean());
        assertFalse(record.path("readOnly").asBoolean());
        assertEquals(1, service.pendingRecordCount(new CloudTrailService.TrailKey(REGION, "management-trail", REGION)));
        service.stopLogging(REGION, "management-trail");
        assertTrue(service.emitManagementEvent(event(REGION)).isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"All,true,true", "WriteOnly,true,true", "ReadOnly,true,false", "All,false,false"})
    void basicSelectorsRespectManagementAndWriteFilters(String mode, boolean management, boolean expected) {
        trail(false);
        service.startLogging(REGION, "management-trail");
        service.putEventSelectors(REGION, "management-trail",
                List.of(new EventSelector(mode, management, List.of(), List.of())));
        assertEquals(expected, service.emitManagementEvent(event(REGION)).isPresent());
    }

    private static AdvancedFieldSelector equalsField(String field, String value) {
        return new AdvancedFieldSelector(field, List.of(value), null, null, null, null, null);
    }

    @ParameterizedTest
    @CsvSource({"eventCategory,Management,true", "eventCategory,Data,false", "eventSource,secretsmanager.amazonaws.com,true",
            "eventSource,kms.amazonaws.com,false", "eventName,PutSecretValue,true", "eventName,UpdateSecret,false",
            "readOnly,false,true", "readOnly,true,false", "eventType,AwsApiCall,true", "unknown,anything,false"})
    void advancedSelectorsEvaluateManagementFields(String field, String value, boolean expected) {
        trail(false);
        service.startLogging(REGION, "management-trail");
        service.putAdvancedEventSelectors(REGION, "management-trail", List.of(new AdvancedEventSelector("management",
                List.of(equalsField("eventCategory", "Management"), equalsField(field, value)))));
        assertEquals(expected, service.emitManagementEvent(event(REGION)).isPresent());
    }

    @Test
    void advancedExclusionsAndSourceExclusionsSuppressEvents() {
        trail(false);
        service.startLogging(REGION, "management-trail");
        service.putEventSelectors(REGION, "management-trail", List.of(new EventSelector("All", true, List.of(), List.of(SOURCE))));
        assertTrue(service.emitManagementEvent(event(REGION)).isEmpty());
        service.putAdvancedEventSelectors(REGION, "management-trail", List.of(new AdvancedEventSelector("excluded",
                List.of(equalsField("eventCategory", "Management"), new AdvancedFieldSelector("eventName", null,
                        List.of("PutSecretValue"), null, null, null, null)))));
        assertTrue(service.emitManagementEvent(event(REGION)).isEmpty());
    }

    @Test
    void multiRegionTrailsStayWithinTheirPartition() {
        trail(false);
        service.startLogging(REGION, "management-trail");
        assertTrue(service.emitManagementEvent(event("eu-west-1")).isEmpty());
        service.updateTrail(REGION, "management-trail", null, null, null, null, true, null, null);
        assertTrue(service.emitManagementEvent(event("eu-west-1")).isPresent());
        assertTrue(service.emitManagementEvent(event("cn-north-1")).isEmpty());
    }

    @Test
    void multipleTrailsRecordTheSameEventIdAndKeepIndependentCopies() {
        trail(false);
        service.createTrail(REGION, "another-trail", "example-audit-logs", null, null, false, false, false, false);
        service.startLogging(REGION, "management-trail");
        service.startLogging(REGION, "another-trail");
        ObjectNode returned = service.emitManagementEvent(event(REGION)).orElseThrow();
        String id = returned.path("eventID").asText();
        returned.put("eventName", "changed-by-caller");
        for (String name : List.of("management-trail", "another-trail")) {
            ObjectNode queued = service.drainPendingRecords(new CloudTrailService.TrailKey(REGION, name, REGION)).getFirst();
            assertEquals(id, queued.path("eventID").asText());
            assertEquals("PutSecretValue", queued.path("eventName").asText());
        }
    }
}
