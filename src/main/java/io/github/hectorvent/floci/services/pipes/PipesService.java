package io.github.hectorvent.floci.services.pipes;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.TagHandler;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@ApplicationScoped
public class PipesService implements TagHandler {

    private static final Logger LOG = Logger.getLogger(PipesService.class);

    private final StorageBackend<String, Pipe> storage;
    private final EmulatorConfig config;
    private final PipesPoller poller;

    @Inject
    public PipesService(StorageFactory storageFactory, EmulatorConfig config, PipesPoller poller) {
        this.storage = storageFactory.create("pipes", "pipes.json",
                new TypeReference<Map<String, Pipe>>() {});
        this.config = config;
        this.poller = poller;
    }

    public void startPersistedPollers() {
        List<Pipe> runningPipes = storage.scan(key -> true).stream()
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

        String key = region + "::" + name;
        if (storage.get(key).isPresent()) {
            throw new AwsException("ConflictException",
                    "Pipe " + name + " already exists.", 409);
        }

        String accountId = config.defaultAccountId();
        String arn = "arn:aws:pipes:" + region + ":" + accountId + ":pipe/" + name;
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

    public Pipe updatePipe(String name, String target, String roleArn, String description,
                           DesiredState desiredState, String enrichment,
                           JsonNode sourceParameters, JsonNode targetParameters,
                           JsonNode enrichmentParameters, String region) {
        String key = region + "::" + name;
        Pipe pipe = storage.get(key)
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "Pipe " + name + " does not exist.", 404));

        if (target != null) pipe.setTarget(target);
        if (roleArn != null) pipe.setRoleArn(roleArn);
        if (description != null) pipe.setDescription(description);
        if (desiredState != null) {
            pipe.setDesiredState(desiredState);
            pipe.setCurrentState(desiredState == DesiredState.RUNNING ? PipeState.RUNNING : PipeState.STOPPED);
            if (desiredState == DesiredState.RUNNING) {
                poller.startPolling(pipe);
            } else {
                poller.stopPolling(pipe);
            }
        }
        if (enrichment != null) pipe.setEnrichment(enrichment);
        if (sourceParameters != null) pipe.setSourceParameters(sourceParameters);
        if (targetParameters != null) pipe.setTargetParameters(targetParameters);
        if (enrichmentParameters != null) pipe.setEnrichmentParameters(enrichmentParameters);

        pipe.setLastModifiedTime(Instant.now());
        storage.put(key, pipe);
        LOG.infov("Updated pipe: {0}", name);
        return pipe;
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
}
