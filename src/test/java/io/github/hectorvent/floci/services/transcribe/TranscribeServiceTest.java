package io.github.hectorvent.floci.services.transcribe;

import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.transcribe.model.VocabularyInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TranscribeServiceTest {

    private static final String DEFAULT_ACCOUNT = "000000000000";
    private static final String DEFAULT_REGION = "us-east-1";

    private final AtomicReference<String> account = new AtomicReference<>(DEFAULT_ACCOUNT);
    private final AtomicReference<String> region = new AtomicReference<>(DEFAULT_REGION);
    private AccountAwareStorageBackend<VocabularyInfo> vocabularyStore;
    private TranscribeService service;

    @BeforeEach
    void setUp() {
        RegionResolver resolver = mock(RegionResolver.class);
        when(resolver.getAccountId()).thenAnswer(ignored -> account.get());
        when(resolver.getRegion()).thenAnswer(ignored -> region.get());
        when(resolver.getDefaultAccountId()).thenReturn(DEFAULT_ACCOUNT);
        when(resolver.getDefaultRegion()).thenReturn(DEFAULT_REGION);

        StorageFactory storageFactory = new StorageFactory(null, null) {
            @Override
            public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                                                            com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, V>> typeReference) {
                @SuppressWarnings("unchecked")
                AccountAwareStorageBackend<V> result =
                        (AccountAwareStorageBackend<V>) (AccountAwareStorageBackend<?>) TranscribeServiceTest.this.vocabularyStore;
                return result;
            }
        };
        vocabularyStore = AccountAwareStorageBackend.inMemory(DEFAULT_ACCOUNT);
        service = new TranscribeService(storageFactory, resolver);
        service.initializeStorage();
    }

    @Test
    void sameNameJobsAreIsolatedByAccountAndRegion() {
        service.startTranscriptionJob("shared-job", "s3://bucket/one.wav", "en-US", "wav");

        account.set("111111111111");
        region.set("eu-west-1");
        service.startTranscriptionJob("shared-job", "s3://bucket/two.wav", "en-US", "wav");

        assertEquals("s3://bucket/two.wav",
                service.getTranscriptionJob("shared-job").media().mediaFileUri());
        assertEquals(1, service.listTranscriptionJobs(null, null, null).summaries().size());

        account.set(DEFAULT_ACCOUNT);
        region.set(DEFAULT_REGION);
        assertEquals("s3://bucket/one.wav",
                service.getTranscriptionJob("shared-job").media().mediaFileUri());
    }

    @Test
    void sameNameVocabulariesAreIsolatedByAccountAndRegion() {
        service.createVocabulary("shared-vocabulary", "en-US");

        account.set("111111111111");
        region.set("eu-west-1");
        service.createVocabulary("shared-vocabulary", "de-DE");

        assertEquals("de-DE", service.getVocabulary("shared-vocabulary").languageCode());
        assertEquals(1, service.listVocabularies(null, null, null).vocabularies().size());

        account.set(DEFAULT_ACCOUNT);
        region.set(DEFAULT_REGION);
        assertEquals("en-US", service.getVocabulary("shared-vocabulary").languageCode());
    }

    @Test
    void legacyVocabularyIsVisibleOnlyInDefaultAccountAndRegion() {
        vocabularyStore.put("legacy-vocabulary",
                new VocabularyInfo("legacy-vocabulary", "en-US", "READY", 1L));

        assertEquals(1, service.listVocabularies(null, null, null).vocabularies().size());
        assertEquals("en-US", service.getVocabulary("legacy-vocabulary").languageCode());

        account.set("111111111111");
        region.set("eu-west-1");
        assertThrows(AwsException.class, () -> service.getVocabulary("legacy-vocabulary"));
        assertTrue(service.listVocabularies(null, null, null).vocabularies().isEmpty());

        account.set(DEFAULT_ACCOUNT);
        region.set("eu-west-1");
        assertThrows(AwsException.class, () -> service.getVocabulary("legacy-vocabulary"));
    }
}
