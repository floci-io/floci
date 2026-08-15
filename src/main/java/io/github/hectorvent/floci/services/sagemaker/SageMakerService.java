package io.github.hectorvent.floci.services.sagemaker;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.sagemaker.model.SageMakerAlgorithm;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Amazon SageMaker control plane. Algorithms are Completed as soon as a create returns, so
 * provider waiters polling {@code DescribeAlgorithm} complete on their first poll rather than
 * modeling a Pending/InProgress transition.
 */
@ApplicationScoped
public class SageMakerService {

    private static final Logger LOG = Logger.getLogger(SageMakerService.class);
    private static final String ARN_RESOURCE_PREFIX = "algorithm/";

    private final StorageBackend<String, SageMakerAlgorithm> algorithms;
    private final RegionResolver regionResolver;

    @Inject
    public SageMakerService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this.algorithms = storageFactory.create("sagemaker", "sagemaker-algorithms.json",
                new TypeReference<Map<String, SageMakerAlgorithm>>() {});
        this.regionResolver = regionResolver;
    }

    public SageMakerAlgorithm createAlgorithm(String name, String description, JsonNode trainingSpecification,
            JsonNode inferenceSpecification, JsonNode validationSpecification, boolean certifyForMarketplace,
            Map<String, String> tags, String region) {
        if (name == null || name.isBlank()) {
            throw new AwsException("ValidationException", "AlgorithmName is required.", 400);
        }
        if (trainingSpecification == null || trainingSpecification.isNull()) {
            throw new AwsException("ValidationException", "TrainingSpecification is required.", 400);
        }
        if (algorithms.get(name).isPresent()) {
            throw new AwsException("ValidationException",
                    "Algorithm with name " + name + " already exists.", 400);
        }

        SageMakerAlgorithm algorithm = new SageMakerAlgorithm();
        algorithm.setAlgorithmArn(regionResolver.buildArn("sagemaker", region, ARN_RESOURCE_PREFIX + name));
        algorithm.setAlgorithmName(name);
        algorithm.setAlgorithmDescription(description);
        algorithm.setTrainingSpecification(trainingSpecification);
        algorithm.setInferenceSpecification(inferenceSpecification);
        algorithm.setValidationSpecification(validationSpecification);
        algorithm.setCertifyForMarketplace(certifyForMarketplace);
        algorithm.setAlgorithmStatus("Completed");
        algorithm.setCreationTime(Instant.now());
        algorithm.setTags(tags != null ? new HashMap<>(tags) : new HashMap<>());

        algorithms.put(name, algorithm);
        LOG.infov("Created SageMaker algorithm: {0}", algorithm.getAlgorithmArn());
        return algorithm;
    }

    public SageMakerAlgorithm describeAlgorithm(String nameOrArn) {
        String name = algorithmNameOf(nameOrArn);
        return algorithms.get(name)
                .orElseThrow(() -> notFound(name));
    }

    public void deleteAlgorithm(String name) {
        describeAlgorithm(name);
        algorithms.delete(algorithmNameOf(name));
        LOG.infov("Deleted SageMaker algorithm: {0}", name);
    }

    public List<SageMakerAlgorithm> listAlgorithms(String nameContains) {
        return algorithms.scan(k -> true).stream()
                .filter(a -> nameContains == null || nameContains.isBlank()
                        || a.getAlgorithmName().contains(nameContains))
                .sorted(Comparator.comparing(SageMakerAlgorithm::getCreationTime))
                .toList();
    }

    public Map<String, String> listTags(String resourceArn) {
        SageMakerAlgorithm algorithm = findByArn(resourceArn);
        return algorithm.getTags() != null ? algorithm.getTags() : Map.of();
    }

    public void addTags(String resourceArn, Map<String, String> tags) {
        SageMakerAlgorithm algorithm = findByArn(resourceArn);
        if (algorithm.getTags() == null) {
            algorithm.setTags(new HashMap<>());
        }
        algorithm.getTags().putAll(tags);
        algorithms.put(algorithm.getAlgorithmName(), algorithm);
    }

    public void deleteTags(String resourceArn, List<String> tagKeys) {
        SageMakerAlgorithm algorithm = findByArn(resourceArn);
        if (algorithm.getTags() != null && tagKeys != null) {
            tagKeys.forEach(algorithm.getTags()::remove);
        }
        algorithms.put(algorithm.getAlgorithmName(), algorithm);
    }

    private SageMakerAlgorithm findByArn(String resourceArn) {
        if (resourceArn == null || resourceArn.isBlank()) {
            throw new AwsException("ValidationException", "ResourceArn is required.", 400);
        }
        String name = algorithmNameOf(resourceArn);
        return algorithms.get(name)
                .orElseThrow(() -> notFound(name));
    }

    /**
     * SageMaker's own {@code service-2.json} declares no {@code errors} list for
     * {@code DescribeAlgorithm} or {@code DeleteAlgorithm}, so the AWS SDK generates no typed
     * exception for either operation and every error surfaces as a generic API error. The real
     * provider source (internal/service/sagemaker/algorithm.go, findAlgorithmByName /
     * waitAlgorithmDeleted) only recognizes a missing algorithm via a
     * {@code ValidationException} whose message contains {@code "does not exist"} — that is
     * genuine AWS behavior the provider was written against, not a code we get to invent.
     * Any other code here (e.g. {@code ResourceNotFound}) is invisible to the waiter and
     * surfaces as an unrecoverable delete-wait failure instead of a completed delete.
     */
    private static AwsException notFound(String name) {
        return new AwsException("ValidationException", "Algorithm " + name + " does not exist.", 400);
    }

    /**
     * {@code DescribeAlgorithm} accepts either a bare algorithm name or a full ARN
     * ({@code ArnOrName} in the AWS model); {@code AddTags}/{@code ListTags}/{@code DeleteTags}
     * always receive a full ARN. Both resolve to the same storage key.
     */
    private static String algorithmNameOf(String arnOrName) {
        if (arnOrName == null || arnOrName.isBlank()) {
            throw new AwsException("ValidationException", "AlgorithmName is required.", 400);
        }
        int idx = arnOrName.indexOf(ARN_RESOURCE_PREFIX);
        return idx >= 0 ? arnOrName.substring(idx + ARN_RESOURCE_PREFIX.length()) : arnOrName;
    }
}
