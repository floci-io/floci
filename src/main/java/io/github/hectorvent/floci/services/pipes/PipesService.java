package io.github.hectorvent.floci.services.pipes;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.resource.ExplorerResource;
import io.github.hectorvent.floci.core.resource.ResourceProvider;
import io.github.hectorvent.floci.core.resource.SupportedResourceType;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.pipes.model.DesiredState;
import io.github.hectorvent.floci.services.pipes.model.Pipe;
import io.github.hectorvent.floci.services.pipes.model.PipeState;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@ApplicationScoped
public class PipesService implements TagHandler, ResourceProvider {

    private static final Logger LOG = Logger.getLogger(PipesService.class);

    private static final int MIN_PARALLELIZATION_FACTOR = 1;
    private static final int MAX_PARALLELIZATION_FACTOR = 10;

    private final StorageBackend<String, Pipe> storage;
    private final RegionResolver regionResolver;
    private final PipesPoller poller;

    @Inject
    public PipesService(StorageFactory storageFactory, RegionResolver regionResolver, PipesPoller poller) {
        this.storage = storageFactory.create("pipes", "pipes.json",
                new TypeReference<Map<String, Pipe>>() {});
        this.regionResolver = regionResolver;
        this.poller = poller;
    }

    public void startPersistedPollers() {
        List<Pipe> allPipes = storage instanceof AccountAwareStorageBackend<Pipe> aware
                ? aware.scanAllAccounts()
                : storage.scan(key -> true);
        List<Pipe> runningPipes = allPipes.stream()
                .filter(pipe -> pipe.getCurrentState() == PipeState.RUNNING)
                .toList();
        for (Pipe pipe : runningPipes) {
            poller.startPolling(pipe);
        }
        if (!runningPipes.isEmpty()) {
            LOG.infov("Resumed polling for {0} pipe(s)", runningPipes.size());
        }
    }

    public Pipe createPipe(String name, String source, String target, String roleArn,
                           String description, DesiredState desiredState, String enrichment,
                           JsonNode sourceParameters, JsonNode targetParameters,
                           JsonNode enrichmentParameters, Map<String, String> tags,
                           String region) {
        if (name == null || name.isBlank()) {
            throw new AwsException("ValidationException", "Name is required", 400);
        }
        if (source == null || source.isBlank()) {
            throw new AwsException("ValidationException", "Source is required", 400);
        }
        if (target == null || target.isBlank()) {
            throw new AwsException("ValidationException", "Target is required", 400);
        }
        if (roleArn == null || roleArn.isBlank()) {
            throw new AwsException("ValidationException", "RoleArn is required", 400);
        }

        validateSourceConfiguration(source, sourceParameters);

        String key = region + "::" + name;
        if (storage.get(key).isPresent()) {
            throw new AwsException("ConflictException",
                    "Pipe " + name + " already exists.", 409);
        }

        String arn = regionResolver.buildArn("pipes", region, "pipe/" + name);
        Instant now = Instant.now();

        Pipe pipe = new Pipe();
        pipe.setName(name);
        pipe.setArn(arn);
        pipe.setSource(source);
        pipe.setTarget(target);
        pipe.setRoleArn(roleArn);
        pipe.setDescription(description);
        DesiredState effectiveDesiredState = desiredState != null ? desiredState : DesiredState.RUNNING;
        pipe.setDesiredState(effectiveDesiredState);
        pipe.setCurrentState(effectiveDesiredState == DesiredState.RUNNING ? PipeState.RUNNING : PipeState.STOPPED);
        pipe.setEnrichment(enrichment);
        pipe.setSourceParameters(sourceParameters);
        pipe.setTargetParameters(targetParameters);
        pipe.setEnrichmentParameters(enrichmentParameters);
        pipe.setTags(tags != null ? new HashMap<>(tags) : new HashMap<>());
        pipe.setCreationTime(now);
        pipe.setLastModifiedTime(now);
        pipe.setAccountId(regionResolver.getAccountId());

        storage.put(key, pipe);
        LOG.infov("Created pipe: {0}", name);

        if (pipe.getCurrentState() == PipeState.RUNNING) {
            poller.startPolling(pipe);
        }
        return pipe;
    }

    public Pipe describePipe(String name, String region) {
        String key = region + "::" + name;
        return storage.get(key)
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "Pipe " + name + " does not exist.", 404));
    }

    /**
     * The UpdatePipe API: a property given as null is left as the pipe holds it.
     */
    public Pipe updatePipe(String name, String target, String roleArn, String description,
                           DesiredState desiredState, String enrichment,
                           JsonNode sourceParameters, JsonNode targetParameters,
                           JsonNode enrichmentParameters, String region) {
        return writePipeConfiguration(name, target, roleArn, description, desiredState, enrichment,
                sourceParameters, targetParameters, enrichmentParameters, region, false);
    }

    /**
     * Puts the pipe back to a configuration held in full: a property given as null is cleared, so
     * the pipe ends up carrying exactly what is passed here and nothing the caller left out.
     *
     * <p>The configuration was accepted when the pipe was written, so it is not validated again:
     * a snapshot that predates a rule added since must still be restorable, or a CloudFormation
     * rollback would fail on the very pipe it is putting back.
     */
    public Pipe restorePipe(String name, String target, String roleArn, String description,
                            DesiredState desiredState, String enrichment,
                            JsonNode sourceParameters, JsonNode targetParameters,
                            JsonNode enrichmentParameters, String region) {
        return writePipeConfiguration(name, target, roleArn, description, desiredState, enrichment,
                sourceParameters, targetParameters, enrichmentParameters, region, true);
    }

    /**
     * Writes the pipe's configuration under the two meanings an unset property carries.
     *
     * <p>With {@code clearUnsetProperties} false, a null property leaves the pipe's value alone:
     * that is what UpdatePipe promises its callers. With it true, a null property clears the pipe's
     * value, which is what putting a pipe back to a configuration recorded in full needs; that
     * configuration was accepted when it was written, so only the update path validates.
     *
     * <p>Validation runs on the same retrieved pipe the write then mutates, so a pipe replaced
     * between two lookups cannot be validated as one pipe and updated as another.
     */
    private Pipe writePipeConfiguration(String name, String target, String roleArn, String description,
                                        DesiredState desiredState, String enrichment,
                                        JsonNode sourceParameters, JsonNode targetParameters,
                                        JsonNode enrichmentParameters, String region,
                                        boolean clearUnsetProperties) {
        String key = region + "::" + name;
        Pipe pipe = storage.get(key)
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "Pipe " + name + " does not exist.", 404));

        if (!clearUnsetProperties) {
            validateSourceConfiguration(pipe.getSource(),
                    sourceParameters != null ? sourceParameters : pipe.getSourceParameters());
        }

        writeProperty(target, clearUnsetProperties, pipe::setTarget);
        writeProperty(roleArn, clearUnsetProperties, pipe::setRoleArn);
        writeProperty(description, clearUnsetProperties, pipe::setDescription);
        writeProperty(enrichment, clearUnsetProperties, pipe::setEnrichment);
        writeProperty(sourceParameters, clearUnsetProperties, pipe::setSourceParameters);
        writeProperty(targetParameters, clearUnsetProperties, pipe::setTargetParameters);
        writeProperty(enrichmentParameters, clearUnsetProperties, pipe::setEnrichmentParameters);
        // A pipe always has a desired state: createPipe defaults it to RUNNING and currentState is
        // read off it. A null therefore leaves both alone on either path, rather than clearing a
        // state the poller and currentState would then contradict.
        if (desiredState != null) {
            pipe.setDesiredState(desiredState);
            pipe.setCurrentState(desiredState == DesiredState.RUNNING ? PipeState.RUNNING : PipeState.STOPPED);
            if (desiredState == DesiredState.RUNNING) {
                poller.startPolling(pipe);
            } else {
                poller.stopPolling(pipe);
            }
        }

        pipe.setLastModifiedTime(Instant.now());
        storage.put(key, pipe);
        LOG.infov("Updated pipe: {0}", name);
        return pipe;
    }

    /** Applies one property under the unset meaning {@code clearUnsetProperties} selects. */
    private static <T> void writeProperty(T value, boolean clearUnsetProperties, Consumer<T> setter) {
        if (value != null || clearUnsetProperties) {
            setter.accept(value);
        }
    }

    public void deletePipe(String name, String region) {
        String key = region + "::" + name;
        Pipe pipe = storage.get(key)
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "Pipe " + name + " does not exist.", 404));
        poller.stopPolling(pipe);
        storage.delete(key);
        LOG.infov("Deleted pipe: {0}", name);
    }

    public List<Pipe> listPipes(String namePrefix, String sourcePrefix, String targetPrefix,
                                DesiredState desiredState, PipeState currentState, String region) {
        String regionPrefix = region + "::";
        return storage.scan(key -> key.startsWith(regionPrefix)).stream()
                .filter(pipe -> namePrefix == null || pipe.getName().startsWith(namePrefix))
                .filter(pipe -> sourcePrefix == null || pipe.getSource().startsWith(sourcePrefix))
                .filter(pipe -> targetPrefix == null || pipe.getTarget().startsWith(targetPrefix))
                .filter(pipe -> desiredState == null || pipe.getDesiredState() == desiredState)
                .filter(pipe -> currentState == null || pipe.getCurrentState() == currentState)
                .collect(Collectors.toList());
    }

    public Pipe startPipe(String name, String region) {
        String key = region + "::" + name;
        Pipe pipe = storage.get(key)
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "Pipe " + name + " does not exist.", 404));

        pipe.setDesiredState(DesiredState.RUNNING);
        pipe.setCurrentState(PipeState.RUNNING);
        pipe.setLastModifiedTime(Instant.now());
        storage.put(key, pipe);
        poller.startPolling(pipe);
        LOG.infov("Started pipe: {0}", name);
        return pipe;
    }

    public Pipe stopPipe(String name, String region) {
        String key = region + "::" + name;
        Pipe pipe = storage.get(key)
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "Pipe " + name + " does not exist.", 404));

        poller.stopPolling(pipe);
        pipe.setDesiredState(DesiredState.STOPPED);
        pipe.setCurrentState(PipeState.STOPPED);
        pipe.setLastModifiedTime(Instant.now());
        storage.put(key, pipe);
        LOG.infov("Stopped pipe: {0}", name);
        return pipe;
    }

    @Override
    public List<ExplorerResource> getResources() {
        List<ExplorerResource> resources = new ArrayList<>();
        for (Pipe pipe : storage.scan(k -> true)) {
            String arn = pipe.getArn();
            if (arn == null) {
                continue;
            }
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
            resources.add(new ExplorerResource(
                    arn, "pipes:pipe", "pipes",
                    parsed.region(), parsed.accountId(),
                    pipe.getCreationTime() != null ? pipe.getCreationTime() : Instant.now(),
                    pipe.getTags() != null ? pipe.getTags() : Map.of()));
        }
        return resources;
    }

    @Override
    public Set<SupportedResourceType> getSupportedResourceTypes() {
        return Set.of(new SupportedResourceType("pipes:pipe", "pipes", true));
    }

    @Override
    public String serviceKey() {
        return "pipes";
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        Pipe pipe = findByArn(arn, region);
        if (pipe.getTags() == null) {
            pipe.setTags(new HashMap<>());
        }
        pipe.getTags().putAll(tags);
        String key = region + "::" + pipe.getName();
        storage.put(key, pipe);
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        Pipe pipe = findByArn(arn, region);
        if (pipe.getTags() != null && tagKeys != null) {
            tagKeys.forEach(pipe.getTags()::remove);
        }
        String key = region + "::" + pipe.getName();
        storage.put(key, pipe);
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        Pipe pipe = findByArn(arn, region);
        return pipe.getTags() != null ? pipe.getTags() : Map.of();
    }

    private Pipe findByArn(String arn, String region) {
        String regionPrefix = region + "::";
        return storage.scan(key -> key.startsWith(regionPrefix)).stream()
                .filter(pipe -> arn.equals(pipe.getArn()))
                .findFirst()
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "Resource " + arn + " does not exist.", 404));
    }

    private void validateSourceConfiguration(String source, JsonNode sourceParameters) {
        if (source == null) {
            return;
        }
        if (source.startsWith("smk://")) {
            requireKafkaParameters(sourceParameters, "SelfManagedKafkaParameters");
        } else if (source.contains(":kafka:")) {
            requireKafkaParameters(sourceParameters, "ManagedStreamingKafkaParameters");
        }
        validateParallelizationFactor(source, sourceParameters);
    }

    private void requireKafkaParameters(JsonNode sourceParameters, String parameterBlock) {
        if (sourceParameters == null || sourceParameters.path(parameterBlock).isMissingNode()) {
            throw new AwsException("ValidationException",
                    "SourceParameters." + parameterBlock + " is required", 400);
        }
        String topicName = sourceParameters.path(parameterBlock).path("TopicName").asText(null);
        if (topicName == null || topicName.isBlank()) {
            throw new AwsException("ValidationException",
                    "SourceParameters." + parameterBlock + ".TopicName is required", 400);
        }
    }

    /**
     * Applies the AWS bounds on {@code ParallelizationFactor}. It is a member of the Kinesis and
     * DynamoDB Stream parameter blocks only, so a value carried by a block that does not describe
     * the pipe's source is rejected rather than silently kept.
     */
    private void validateParallelizationFactor(String source, JsonNode sourceParameters) {
        if (sourceParameters == null) {
            return;
        }
        validateParallelizationFactorBlock(sourceParameters, "KinesisStreamParameters",
                "Kinesis stream", source.contains(":kinesis:"));
        validateParallelizationFactorBlock(sourceParameters, "DynamoDBStreamParameters",
                "DynamoDB Stream", source.contains(":dynamodb:"));
    }

    private void validateParallelizationFactorBlock(JsonNode sourceParameters, String parameterBlock,
                                                    String sourceDescription, boolean sourceMatchesBlock) {
        JsonNode factor = sourceParameters.path(parameterBlock).path("ParallelizationFactor");
        if (factor.isMissingNode() || factor.isNull()) {
            return;
        }
        String property = "SourceParameters." + parameterBlock + ".ParallelizationFactor";
        if (!sourceMatchesBlock) {
            throw new AwsException("ValidationException",
                    property + " is only supported for " + sourceDescription + " sources", 400);
        }
        if (!factor.isNumber()) {
            throw new AwsException("ValidationException",
                    property + " must be a numeric value", 400);
        }
        if (!factor.isIntegralNumber()) {
            throw new AwsException("ValidationException",
                    property + " must be an integer", 400);
        }
        // canConvertToInt keeps a value wider than an int out of the bounds check: asLong would
        // hand back the low 64 bits of a BigInteger, so 2^64 + 5 would read as 5 and be accepted.
        if (!factor.canConvertToInt()
                || factor.intValue() < MIN_PARALLELIZATION_FACTOR
                || factor.intValue() > MAX_PARALLELIZATION_FACTOR) {
            throw new AwsException("ValidationException",
                    property + " must be between " + MIN_PARALLELIZATION_FACTOR + " and "
                            + MAX_PARALLELIZATION_FACTOR + " (got " + factor.asText() + ")", 400);
        }
    }
}
