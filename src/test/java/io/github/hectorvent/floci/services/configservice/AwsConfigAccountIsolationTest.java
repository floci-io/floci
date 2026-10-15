package io.github.hectorvent.floci.services.configservice;

import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.configservice.model.ConfigEvaluation;
import io.github.hectorvent.floci.services.configservice.model.ConfigRule;
import io.github.hectorvent.floci.services.configservice.model.ConfigRuleSource;
import io.github.hectorvent.floci.services.configservice.model.ConfigurationRecorder;
import io.github.hectorvent.floci.services.configservice.model.DeliveryChannel;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AwsConfigAccountIsolationTest {
    private static final String REGION = "us-east-1";
    private static final String FIRST = "111111111111";
    private static final String SECOND = "222222222222";
    private final RequestContext context = new RequestContext();
    private AwsConfigService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        Instance<RequestContext> contexts = mock(Instance.class);
        when(contexts.get()).thenReturn(context);
        RegionResolver resolver = mock(RegionResolver.class);
        when(resolver.getAccountId()).thenAnswer(ignored -> context.getAccountId());
        StorageFactory factory = mock(StorageFactory.class);
        when(factory.create(eq("config"), anyString(), any())).thenAnswer(ignored ->
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), contexts, FIRST));
        context.setAccountId(FIRST);
        service = new AwsConfigService(resolver, factory);
        service.initializeStorage();
    }

    @Test
    void recorderStartStopAndDeletionStayInTheirAccount() {
        for (String account : List.of(FIRST, SECOND)) {
            context.setAccountId(account);
            service.putConfigurationRecorder(REGION, new ConfigurationRecorder("default", "role", null));
            service.putDeliveryChannel(REGION, new DeliveryChannel("default", "bucket", null, null, null, null));
        }
        context.setAccountId(FIRST);
        service.startConfigurationRecorder(REGION, "default");
        context.setAccountId(SECOND);
        assertFalse(service.describeConfigurationRecorderStatus(REGION, null).getFirst().recording());
        assertNull(service.describeConfigurationRecorderStatus(REGION, null).getFirst().lastStartTime());
        service.deleteDeliveryChannel(REGION, "default");
        service.startConfigurationRecorder(REGION, "default");
        service.stopConfigurationRecorder(REGION, "default");
        context.setAccountId(FIRST);
        assertTrue(service.describeConfigurationRecorderStatus(REGION, null).getFirst().recording());
        assertNull(service.describeConfigurationRecorderStatus(REGION, null).getFirst().lastStopTime());
        context.setAccountId(SECOND);
        service.deleteConfigurationRecorder(REGION, "default");
        context.setAccountId(FIRST);
        assertTrue(service.describeConfigurationRecorderStatus(REGION, null).getFirst().recording());
        assertNotNull(service.describeConfigurationRecorderStatus(REGION, null).getFirst().lastStartTime());
    }

    @Test
    void ruleInvocationAndDeletionDoNotChangeAnotherAccountsTiming() {
        for (String account : List.of(FIRST, SECOND)) {
            context.setAccountId(account);
            service.putConfigRule(REGION, new ConfigRule("shared", null, null, null, null,
                    new ConfigRuleSource("CUSTOM_LAMBDA", "check", null, null),
                    null, null, null, null, null));
        }
        context.setAccountId(FIRST);
        service.startConfigRulesEvaluation(REGION, List.of("shared"));
        context.setAccountId(SECOND);
        assertFalse(service.describeConfigRuleEvaluationStatus(REGION, null, null, null)
                .items().getFirst().firstEvaluationStarted());
        service.putEvaluations(REGION, "shared", List.of(new ConfigEvaluation("AWS::S3::Bucket", "bucket",
                "COMPLIANT", null, 1700000000.0, null, null)), false);
        service.deleteConfigRule(REGION, "shared");
        context.setAccountId(FIRST);
        assertNotNull(service.describeConfigRuleEvaluationStatus(REGION, null, null, null)
                .items().getFirst().firstActivatedTime());
        assertTrue(service.describeConfigRuleEvaluationStatus(REGION, null, null, null)
                .items().getFirst().firstEvaluationStarted());
        assertEquals("INSUFFICIENT_DATA", service.complianceForRule(REGION, "shared").complianceType());
    }
}
