package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A template persisted before this class collapsed onto a single {@link LaunchTemplateData} also
 * carried the current version's {@code iamInstanceProfileArn} and {@code instanceTags} as flat
 * fields directly on this class, alongside the equivalent per-version fields inside
 * {@link LaunchTemplateData} (see the legacy setters there for the {@code versions} map side).
 * The setters below map that top-level shape onto {@link #data} at load time.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class LaunchTemplate {

    private String launchTemplateId;
    private String launchTemplateName;
    private String defaultVersionNumber = "1";
    private String latestVersionNumber = "1";
    private Instant createTime;
    private String createdBy;
    private String region;
    private List<Tag> tags = new ArrayList<>();
    private LaunchTemplateData data = new LaunchTemplateData();
    private Map<String, LaunchTemplateData> versions = new LinkedHashMap<>();
    private String versionDescription;
    private Map<String, String> versionDescriptions = new LinkedHashMap<>();

    public LaunchTemplate() {}

    public String getLaunchTemplateId() { return launchTemplateId; }
    public void setLaunchTemplateId(String launchTemplateId) { this.launchTemplateId = launchTemplateId; }

    public String getLaunchTemplateName() { return launchTemplateName; }
    public void setLaunchTemplateName(String launchTemplateName) { this.launchTemplateName = launchTemplateName; }

    public String getDefaultVersionNumber() { return defaultVersionNumber; }
    public void setDefaultVersionNumber(String defaultVersionNumber) { this.defaultVersionNumber = defaultVersionNumber; }

    public String getLatestVersionNumber() { return latestVersionNumber; }
    public void setLatestVersionNumber(String latestVersionNumber) { this.latestVersionNumber = latestVersionNumber; }

    public Instant getCreateTime() { return createTime; }
    public void setCreateTime(Instant createTime) { this.createTime = createTime; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public List<Tag> getTags() { return tags; }
    public void setTags(List<Tag> tags) {
        this.tags = tags != null ? new ArrayList<>(tags) : new ArrayList<>();
    }

    /** The data of the version this instance represents — the latest one unless copied for another. */
    public LaunchTemplateData getData() { return data; }
    public void setData(LaunchTemplateData data) {
        this.data = data != null ? data : new LaunchTemplateData();
    }

    public Map<String, LaunchTemplateData> getVersions() { return versions; }
    public void setVersions(Map<String, LaunchTemplateData> versions) {
        this.versions = versions != null ? new LinkedHashMap<>(versions) : new LinkedHashMap<>();
    }

    /** The {@code VersionDescription} of the version this instance represents — see {@link #getData()}. */
    public String getVersionDescription() { return versionDescription; }
    public void setVersionDescription(String versionDescription) { this.versionDescription = versionDescription; }

    /**
     * {@code VersionDescription} is a version-level field on {@code LaunchTemplateVersion}, not a
     * member of {@code RequestLaunchTemplateData} / {@code ResponseLaunchTemplateData}, so it is
     * tracked in this parallel map — keyed by version number, the same way {@link #versions} is —
     * rather than inside {@link LaunchTemplateData} itself.
     */
    public Map<String, String> getVersionDescriptions() { return versionDescriptions; }
    public void setVersionDescriptions(Map<String, String> versionDescriptions) {
        this.versionDescriptions = versionDescriptions != null ? new LinkedHashMap<>(versionDescriptions) : new LinkedHashMap<>();
    }

    // ── Pre-unified-data schema migration ────────────────────────────────────────────────
    //
    // See the class-level note above. Deserialize-only — there is no getter for either legacy
    // key, so a file saved today never writes this shape back out. Guarded the same way as
    // LaunchTemplateData's equivalent setters: a current-schema record never carries these keys,
    // so this only ever fires for a pre-migration file, whichever order the keys load in.

    @JsonSetter("iamInstanceProfileArn")
    public void setLegacyIamInstanceProfileArn(String arn) {
        if (arn != null && !arn.isBlank() && data.getIamInstanceProfile() == null) {
            data.setIamInstanceProfile(new LaunchTemplateData.IamInstanceProfile(arn, null));
        }
    }

    @JsonSetter("instanceTags")
    public void setLegacyInstanceTags(List<Tag> instanceTags) {
        if (instanceTags != null && !instanceTags.isEmpty() && data.getTagSpecifications().isEmpty()) {
            data.getTagSpecifications().add(new LaunchTemplateData.TagSpecification("instance", instanceTags));
        }
    }
}
