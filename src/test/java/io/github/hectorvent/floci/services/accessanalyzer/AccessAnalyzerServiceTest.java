package io.github.hectorvent.floci.services.accessanalyzer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.accessanalyzer.model.Analyzer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccessAnalyzerServiceTest {
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_ID = "123456789012";

    private final ObjectMapper mapper = new ObjectMapper();
    private AccessAnalyzerService service;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        StorageFactory storageFactory = mock(StorageFactory.class);
        AccountAwareStorageBackend<Analyzer> store = AccountAwareStorageBackend.inMemory(ACCOUNT_ID);
        when(storageFactory.create(eq("accessanalyzer"), eq("accessanalyzer-analyzers.json"), any(TypeReference.class)))
                .thenReturn((AccountAwareStorageBackend) store);

        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.buildArn(eq("access-analyzer"), eq(REGION), any(String.class)))
                .thenAnswer(invocation -> "arn:aws:access-analyzer:" + REGION + ":" + ACCOUNT_ID + ":"
                        + invocation.getArgument(2, String.class));
        service = new AccessAnalyzerService(storageFactory, regionResolver);
    }

    @Test
    void quotasAreAppliedPerExactAnalyzerType() {
        service.createAnalyzer(request("external", "ACCOUNT"), REGION);
        service.createAnalyzer(request("unused", "ACCOUNT_UNUSED_ACCESS"), REGION);

        AwsException duplicateType = assertThrows(AwsException.class,
                () -> service.createAnalyzer(request("external-2", "ACCOUNT"), REGION));
        assertEquals("ServiceQuotaExceededException", duplicateType.getErrorCode());
    }

    @Test
    void organizationInternalAccessLimitIsOne() {
        service.createAnalyzer(request("internal", "ORGANIZATION_INTERNAL_ACCESS"), REGION);
        AwsException duplicateType = assertThrows(AwsException.class,
                () -> service.createAnalyzer(request("internal-2", "ORGANIZATION_INTERNAL_ACCESS"), REGION));
        assertEquals("ServiceQuotaExceededException", duplicateType.getErrorCode());
    }

    @Test
    void clearRemovesPersistedState() {
        service.createAnalyzer(request("reset-me", "ACCOUNT"), REGION);
        service.clear();
        assertTrue(service.listAnalyzers(REGION, null, null, null).items().isEmpty());
    }

    private ObjectNode request(String name, String type) {
        ObjectNode request = mapper.createObjectNode();
        request.put("analyzerName", name);
        request.put("type", type);
        return request;
    }
}
