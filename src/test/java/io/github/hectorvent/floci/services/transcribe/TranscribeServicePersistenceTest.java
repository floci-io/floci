package io.github.hectorvent.floci.services.transcribe;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.transcribe.model.VocabularyInfo;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies Transcribe vocabularies survive a restart. Two service instances share the same
 * {@link StorageFactory} backend; the second simulates a process restart reloading from disk.
 */
class TranscribeServicePersistenceTest {

    @Test
    void vocabulariesSurviveRestart() {
        SharedStorageFactory storage = new SharedStorageFactory();

        TranscribeService first = serviceWithStorage(storage);
        first.createVocabulary("medical-terms", "en-US");

        TranscribeService reloaded = serviceWithStorage(storage);
        VocabularyInfo vocab = reloaded.getVocabulary("medical-terms");
        assertEquals("en-US", vocab.languageCode());
        assertEquals("READY", vocab.vocabularyState());
    }

    @Test
    void deletedVocabularyDoesNotReappearAfterRestart() {
        SharedStorageFactory storage = new SharedStorageFactory();

        TranscribeService first = serviceWithStorage(storage);
        first.createVocabulary("keep", "en-US");
        first.createVocabulary("drop", "es-ES");
        first.deleteVocabulary("drop");

        TranscribeService reloaded = serviceWithStorage(storage);
        assertEquals("en-US", reloaded.getVocabulary("keep").languageCode());
        assertThrows(AwsException.class, () -> reloaded.getVocabulary("drop"));
        assertTrue(reloaded.listVocabularies(null, null, null).vocabularies().stream()
                .noneMatch(v -> v.vocabularyName().equals("drop")));
    }

    private static TranscribeService serviceWithStorage(StorageFactory storage) {
        TranscribeService service = new TranscribeService(storage);
        service.initializeStorage();
        return service;
    }

    private static final class SharedStorageFactory extends StorageFactory {
        private final Map<String, StorageBackend<String, ?>> stores = new HashMap<>();

        private SharedStorageFactory() {
            super(null, null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> StorageBackend<String, V> create(String serviceName,
                                                    String fileName,
                                                    TypeReference<Map<String, V>> typeReference) {
            return (StorageBackend<String, V>) stores.computeIfAbsent(fileName, ignored -> new InMemoryStorage<>());
        }
    }
}
