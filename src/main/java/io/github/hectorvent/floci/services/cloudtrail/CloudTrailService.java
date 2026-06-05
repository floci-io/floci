package io.github.hectorvent.floci.services.cloudtrail;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudtrail.model.CloudTrailTrail;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class CloudTrailService {
    private final StorageBackend<String, CloudTrailTrail> trailStore;
    private final RegionResolver regionResolver;

    @Inject
    public CloudTrailService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this.trailStore = storageFactory.create("cloudtrail", "cloudtrail-trails.json",
                new TypeReference<Map<String, CloudTrailTrail>>() {});
        this.regionResolver = regionResolver;
    }

    public CloudTrailTrail createTrail(String region, String name, String s3BucketName,
                                       boolean includeGlobalServiceEvents, boolean multiRegionTrail,
                                       boolean organizationTrail, Map<String, String> tags) {
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidTrailNameException", "Trail name is required", 400);
        }
        if (trailStore.get(key(region, name)).isPresent()) {
            throw new AwsException("TrailAlreadyExistsException", "Trail " + name + " already exists", 400);
        }

        CloudTrailTrail trail = new CloudTrailTrail();
        Instant now = Instant.now();
        trail.setName(name);
        trail.setTrailArn(trailArn(region, name));
        trail.setS3BucketName(s3BucketName);
        trail.setIncludeGlobalServiceEvents(includeGlobalServiceEvents);
        trail.setMultiRegionTrail(multiRegionTrail);
        trail.setOrganizationTrail(organizationTrail);
        trail.setHomeRegion(region);
        trail.setCreated(now);
        trail.setUpdated(now);
        trail.setTags(new HashMap<>(tags));
        trailStore.put(key(region, name), trail);
        return trail;
    }

    public CloudTrailTrail updateTrail(String region, String nameOrArn, String s3BucketName,
                                       Boolean includeGlobalServiceEvents, Boolean multiRegionTrail) {
        CloudTrailTrail trail = requireTrail(region, nameOrArn);
        if (s3BucketName != null && !s3BucketName.isBlank()) {
            trail.setS3BucketName(s3BucketName);
        }
        if (includeGlobalServiceEvents != null) {
            trail.setIncludeGlobalServiceEvents(includeGlobalServiceEvents);
        }
        if (multiRegionTrail != null) {
            trail.setMultiRegionTrail(multiRegionTrail);
        }
        trail.setUpdated(Instant.now());
        trailStore.put(key(trail.getHomeRegion(), trail.getName()), trail);
        return trail;
    }

    public List<CloudTrailTrail> describeTrails(String region, List<String> namesOrArns) {
        if (namesOrArns == null || namesOrArns.isEmpty()) {
            return trailStore.scan(key -> key.startsWith(region + ":"));
        }
        return namesOrArns.stream()
                .map(nameOrArn -> findTrail(region, nameOrArn))
                .flatMap(java.util.Optional::stream)
                .toList();
    }

    public CloudTrailTrail requireTrail(String region, String nameOrArn) {
        return findTrail(region, nameOrArn)
                .orElseThrow(() -> new AwsException("TrailNotFoundException",
                        "Trail " + nameOrArn + " does not exist", 400));
    }

    public void startLogging(String region, String nameOrArn) {
        CloudTrailTrail trail = requireTrail(region, nameOrArn);
        trail.setLogging(true);
        trail.setUpdated(Instant.now());
        trailStore.put(key(trail.getHomeRegion(), trail.getName()), trail);
    }

    public void stopLogging(String region, String nameOrArn) {
        CloudTrailTrail trail = requireTrail(region, nameOrArn);
        trail.setLogging(false);
        trail.setUpdated(Instant.now());
        trailStore.put(key(trail.getHomeRegion(), trail.getName()), trail);
    }

    public void deleteTrail(String region, String nameOrArn) {
        CloudTrailTrail trail = requireTrail(region, nameOrArn);
        trailStore.delete(key(trail.getHomeRegion(), trail.getName()));
    }

    public void putEventSelectors(String region, String nameOrArn) {
        requireTrail(region, nameOrArn);
    }

    private java.util.Optional<CloudTrailTrail> findTrail(String region, String nameOrArn) {
        String name = nameFromNameOrArn(nameOrArn);
        if (name == null || name.isBlank()) {
            return java.util.Optional.empty();
        }
        java.util.Optional<CloudTrailTrail> sameRegion = trailStore.get(key(region, name));
        if (sameRegion.isPresent()) {
            return sameRegion;
        }
        return trailStore.scan(ignored -> true).stream()
                .filter(trail -> name.equals(trail.getName()) || nameOrArn.equals(trail.getTrailArn()))
                .findFirst();
    }

    private String trailArn(String region, String name) {
        return regionResolver.buildArn("cloudtrail", region, "trail/" + name);
    }

    private static String key(String region, String name) {
        return region + ":" + name;
    }

    private static String nameFromNameOrArn(String nameOrArn) {
        if (nameOrArn == null) {
            return null;
        }
        int slash = nameOrArn.lastIndexOf('/');
        return slash >= 0 ? nameOrArn.substring(slash + 1) : nameOrArn;
    }
}
