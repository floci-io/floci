package io.github.hectorvent.floci.services.iot;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.FlociCertificateAuthority;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsService;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbService;
import io.github.hectorvent.floci.services.firehose.FirehoseService;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iot.model.IotCertificate;
import io.github.hectorvent.floci.services.iot.model.IotPolicy;
import io.github.hectorvent.floci.services.iot.model.Thing;
import io.github.hectorvent.floci.services.kinesis.KinesisService;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.sns.SnsService;
import io.github.hectorvent.floci.services.sqs.SqsService;

import java.util.Set;

import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An {@link IotService} over in-memory stores, account-aware as in production or plain, with
 * every collaborator that a device authorization test does not exercise mocked. The account-aware
 * stores stay reachable so a test can place a record in another account's partition, which no
 * API call does.
 */
final class IotServiceTestSupport {

    static final String ACCOUNT = "000000000000";

    final AccountAwareStorageBackend<Thing> things = AccountAwareStorageBackend.inMemory(ACCOUNT);
    final AccountAwareStorageBackend<IotCertificate> certificates = AccountAwareStorageBackend.inMemory(ACCOUNT);
    final AccountAwareStorageBackend<IotPolicy> policies = AccountAwareStorageBackend.inMemory(ACCOUNT);
    final AccountAwareStorageBackend<Set<String>> policyAttachments = AccountAwareStorageBackend.inMemory(ACCOUNT);
    final AccountAwareStorageBackend<Set<String>> thingPrincipals = AccountAwareStorageBackend.inMemory(ACCOUNT);
    final IotService service;

    IotServiceTestSupport(String region, FlociCertificateAuthority certificateAuthority) {
        this(region, certificateAuthority, true);
    }

    IotServiceTestSupport(String region, FlociCertificateAuthority certificateAuthority, boolean accountAware) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultRegion()).thenReturn(region);
        when(config.services().iot().ruleSqlStrict()).thenReturn(false);
        ObjectMapper objectMapper = new ObjectMapper();
        service = new IotService(
                accountAware ? things : new InMemoryStorage<>(),
                accountAware ? certificates : new InMemoryStorage<>(),
                accountAware ? policies : new InMemoryStorage<>(),
                accountAware ? policyAttachments : new InMemoryStorage<>(),
                accountAware ? thingPrincipals : new InMemoryStorage<>(),
                AccountAwareStorageBackend.inMemory(ACCOUNT),   // shadows
                AccountAwareStorageBackend.inMemory(ACCOUNT),   // topic rules
                AccountAwareStorageBackend.inMemory(ACCOUNT),   // retained messages
                AccountAwareStorageBackend.inMemory(ACCOUNT),   // jobs
                AccountAwareStorageBackend.inMemory(ACCOUNT),   // job executions
                AccountAwareStorageBackend.inMemory(ACCOUNT),   // thing types
                AccountAwareStorageBackend.inMemory(ACCOUNT),   // thing groups
                AccountAwareStorageBackend.inMemory(ACCOUNT),   // thing group memberships
                config,
                new RegionResolver(region, ACCOUNT),
                objectMapper,
                new IotPublishEventRecorder(),
                mock(IotMqttBrokerService.class),
                mock(SqsService.class),
                mock(SnsService.class),
                mock(S3Service.class),
                mock(KinesisService.class),
                mock(DynamoDbService.class),
                mock(LambdaService.class),
                mock(FirehoseService.class),
                mock(CloudWatchLogsService.class),
                certificateAuthority,
                new IamPolicyEvaluator(objectMapper));
    }
}
