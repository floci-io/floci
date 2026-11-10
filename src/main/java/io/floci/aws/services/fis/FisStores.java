package io.floci.aws.services.fis;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.fis.model.Experiment;
import io.floci.aws.services.fis.model.ExperimentTemplate;
import io.floci.aws.services.fis.model.IdempotencyRecord;
import io.floci.aws.services.fis.model.ResolvedTarget;
import io.floci.aws.services.fis.model.SafetyLever;
import io.floci.aws.services.fis.model.TargetAccountConfiguration;

import java.util.Map;

final class FisStores {
    final StorageBackend<String, ExperimentTemplate> templates;
    final StorageBackend<String, Experiment> experiments;
    final StorageBackend<String, TargetAccountConfiguration> targetAccounts;
    final StorageBackend<String, ResolvedTarget> resolvedTargets;
    final StorageBackend<String, SafetyLever> safetyLevers;
    final StorageBackend<String, Map<String, String>> actionTags;
    final StorageBackend<String, IdempotencyRecord> idempotency;

    FisStores(StorageFactory storageFactory) {
        this(
                storageFactory.create("fis", "fis-experiment-templates.json",
                        new TypeReference<Map<String, ExperimentTemplate>>() {}),
                storageFactory.create("fis", "fis-experiments.json",
                        new TypeReference<Map<String, Experiment>>() {}),
                storageFactory.create("fis", "fis-target-account-configurations.json",
                        new TypeReference<Map<String, TargetAccountConfiguration>>() {}),
                storageFactory.create("fis", "fis-resolved-targets.json",
                        new TypeReference<Map<String, ResolvedTarget>>() {}),
                storageFactory.create("fis", "fis-safety-levers.json",
                        new TypeReference<Map<String, SafetyLever>>() {}),
                storageFactory.create("fis", "fis-action-tags.json",
                        new TypeReference<Map<String, Map<String, String>>>() {}),
                storageFactory.create("fis", "fis-idempotency.json",
                        new TypeReference<Map<String, IdempotencyRecord>>() {}));
    }

    FisStores(
            StorageBackend<String, ExperimentTemplate> templates,
            StorageBackend<String, Experiment> experiments,
            StorageBackend<String, TargetAccountConfiguration> targetAccounts,
            StorageBackend<String, ResolvedTarget> resolvedTargets,
            StorageBackend<String, SafetyLever> safetyLevers,
            StorageBackend<String, Map<String, String>> actionTags,
            StorageBackend<String, IdempotencyRecord> idempotency) {
        this.templates = templates;
        this.experiments = experiments;
        this.targetAccounts = targetAccounts;
        this.resolvedTargets = resolvedTargets;
        this.safetyLevers = safetyLevers;
        this.actionTags = actionTags;
        this.idempotency = idempotency;
    }

    static FisStores inMemory() {
        return new FisStores(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>());
    }
}
