package io.github.hectorvent.floci.services.medialive;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.medialive.model.MediaLiveMultiplex;
import io.github.hectorvent.floci.services.medialive.model.MediaLiveMultiplexProgram;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AWS Elemental MediaLive management plane: multiplexes and multiplex programs
 * only. A multiplex is IDLE as soon as a create returns, so SDK and Terraform
 * waiters complete on their first poll; a deleted multiplex stays readable in
 * state DELETED because the SDK's MultiplexDeleted waiter polls DescribeMultiplex
 * for that state rather than treating NotFound as success. The video transport
 * data plane is not emulated.
 */
@ApplicationScoped
public class MediaLiveService implements TagHandler {

    private static final Logger LOG = Logger.getLogger(MediaLiveService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StorageBackend<String, MediaLiveMultiplex> multiplexes;
    private final StorageBackend<String, MediaLiveMultiplexProgram> programs;
    private final RegionResolver regionResolver;

    @Inject
    public MediaLiveService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this.multiplexes = storageFactory.create("medialive", "medialive-multiplexes.json",
                new TypeReference<Map<String, MediaLiveMultiplex>>() {});
        this.programs = storageFactory.create("medialive", "medialive-multiplex-programs.json",
                new TypeReference<Map<String, MediaLiveMultiplexProgram>>() {});
        this.regionResolver = regionResolver;
    }

    // ──────────────────────────── Multiplexes ────────────────────────────

    public MediaLiveMultiplex createMultiplex(String name, List<String> availabilityZones,
                                              JsonNode multiplexSettings, Map<String, String> tags,
                                              String region) {
        if (name == null || name.isBlank()) {
            throw new AwsException("BadRequestException", "name is required", 400);
        }
        String id = numericId();
        MediaLiveMultiplex multiplex = new MediaLiveMultiplex();
        multiplex.setId(id);
        multiplex.setArn(regionResolver.buildArn("medialive", region, "multiplex:" + id));
        multiplex.setName(name);
        multiplex.setAvailabilityZones(availabilityZones != null
                ? new ArrayList<>(availabilityZones) : new ArrayList<>());
        multiplex.setMultiplexSettings(multiplexSettings);
        multiplex.setState("IDLE");
        multiplex.setTags(tags != null ? new HashMap<>(tags) : new HashMap<>());
        multiplex.setAccountId(regionResolver.getAccountId());

        multiplexes.put(id, multiplex);
        LOG.infov("Created MediaLive multiplex: {0}", multiplex.getArn());
        return multiplex;
    }

    public MediaLiveMultiplex getMultiplex(String id) {
        return multiplexes.get(id)
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "Multiplex " + id + " not found", 404));
    }

    public MediaLiveMultiplex deleteMultiplex(String id) {
        MediaLiveMultiplex multiplex = getMultiplex(id);
        multiplex.setState("DELETED");
        multiplexes.put(id, multiplex);
        LOG.infov("Deleted MediaLive multiplex: {0}", id);
        return multiplex;
    }

    public int programCount(String multiplexId) {
        return programs.scan(k -> k.startsWith(multiplexId + "/")).size();
    }

    // ─────────────────── Listings (Cloud Control bridge) ──────────────────

    /**
     * All multiplexes except those in state DELETED: a deleted multiplex stays
     * readable for the SDK's waiter (see the class doc) but must not be
     * enumerated as live.
     */
    public List<MediaLiveMultiplex> listMultiplexes() {
        return multiplexes.scan(k -> true).stream()
                .filter(m -> !"DELETED".equals(m.getState()))
                .toList();
    }

    // ──────────────────────────── Programs ────────────────────────────

    public MediaLiveMultiplexProgram createProgram(String multiplexId, String programName,
                                                   JsonNode multiplexProgramSettings) {
        getMultiplex(multiplexId);
        if (programName == null || programName.isBlank()) {
            throw new AwsException("BadRequestException", "programName is required", 400);
        }
        MediaLiveMultiplexProgram program = new MediaLiveMultiplexProgram();
        program.setMultiplexId(multiplexId);
        program.setProgramName(programName);
        program.setMultiplexProgramSettings(multiplexProgramSettings);

        programs.put(programKey(multiplexId, programName), program);
        LOG.infov("Created MediaLive multiplex program: {0}/{1}", multiplexId, programName);
        return program;
    }

    public MediaLiveMultiplexProgram getProgram(String multiplexId, String programName) {
        return programs.get(programKey(multiplexId, programName))
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "Program " + programName + " not found in multiplex " + multiplexId, 404));
    }

    public MediaLiveMultiplexProgram deleteProgram(String multiplexId, String programName) {
        MediaLiveMultiplexProgram program = getProgram(multiplexId, programName);
        programs.delete(programKey(multiplexId, programName));
        LOG.infov("Deleted MediaLive multiplex program: {0}/{1}", multiplexId, programName);
        return program;
    }

    private static String programKey(String multiplexId, String programName) {
        return multiplexId + "/" + programName;
    }

    // ──────────────────────────── Tags ────────────────────────────
    //
    // MediaLive's tag operations live under the service's own /prod/tags path,
    // which MediaLiveController routes here directly; registering as a
    // TagHandler additionally lets the shared /tags dispatcher resolve
    // medialive ARNs, at no cost.

    @Override
    public String serviceKey() {
        return "medialive";
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        Map<String, String> tags = findByArn(arn).getTags();
        return tags != null ? tags : Map.of();
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        MediaLiveMultiplex multiplex = findByArn(arn);
        if (multiplex.getTags() == null) {
            multiplex.setTags(new HashMap<>());
        }
        multiplex.getTags().putAll(tags);
        multiplexes.put(multiplex.getId(), multiplex);
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        MediaLiveMultiplex multiplex = findByArn(arn);
        if (multiplex.getTags() != null && tagKeys != null) {
            tagKeys.forEach(multiplex.getTags()::remove);
        }
        multiplexes.put(multiplex.getId(), multiplex);
    }

    private MediaLiveMultiplex findByArn(String arn) {
        return multiplexes.scan(k -> true).stream()
                .filter(m -> arn.equals(m.getArn()))
                .findFirst()
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "Resource " + arn + " does not exist.", 404));
    }

    private static String numericId() {
        StringBuilder id = new StringBuilder(7);
        id.append(1 + RANDOM.nextInt(9));
        for (int i = 1; i < 7; i++) {
            id.append(RANDOM.nextInt(10));
        }
        return id.toString();
    }
}
