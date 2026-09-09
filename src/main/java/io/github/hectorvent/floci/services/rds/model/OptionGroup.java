package io.github.hectorvent.floci.services.rds.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An RDS option group — the container engine-specific options are enabled through
 * (Oracle {@code S3_INTEGRATION}, SQL Server {@code SQLSERVER_BACKUP_RESTORE},
 * MariaDB {@code MARIADB_AUDIT_PLUGIN}, ...).
 */
@RegisterForReflection
public class OptionGroup {

    private String optionGroupName;
    private String optionGroupDescription;
    private String engineName;
    private String majorEngineVersion;
    private String optionGroupArn;
    private boolean allowsVpcAndNonVpcInstanceMemberships = true;
    private String region;
    private List<OptionGroupOption> options = new ArrayList<>();
    private Map<String, String> tags = new LinkedHashMap<>();

    public OptionGroup() {}

    public OptionGroup(String optionGroupName, String engineName,
                       String majorEngineVersion, String optionGroupDescription) {
        this.optionGroupName = optionGroupName;
        this.engineName = engineName;
        this.majorEngineVersion = majorEngineVersion;
        this.optionGroupDescription = optionGroupDescription;
    }

    public String getOptionGroupName() { return optionGroupName; }
    public void setOptionGroupName(String optionGroupName) { this.optionGroupName = optionGroupName; }

    public String getOptionGroupDescription() { return optionGroupDescription; }
    public void setOptionGroupDescription(String optionGroupDescription) { this.optionGroupDescription = optionGroupDescription; }

    public String getEngineName() { return engineName; }
    public void setEngineName(String engineName) { this.engineName = engineName; }

    public String getMajorEngineVersion() { return majorEngineVersion; }
    public void setMajorEngineVersion(String majorEngineVersion) { this.majorEngineVersion = majorEngineVersion; }

    public String getOptionGroupArn() { return optionGroupArn; }
    public void setOptionGroupArn(String optionGroupArn) { this.optionGroupArn = optionGroupArn; }

    public boolean isAllowsVpcAndNonVpcInstanceMemberships() { return allowsVpcAndNonVpcInstanceMemberships; }
    public void setAllowsVpcAndNonVpcInstanceMemberships(boolean allowsVpcAndNonVpcInstanceMemberships) {
        this.allowsVpcAndNonVpcInstanceMemberships = allowsVpcAndNonVpcInstanceMemberships;
    }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public List<OptionGroupOption> getOptions() { return options; }
    public void setOptions(List<OptionGroupOption> options) {
        this.options = options == null ? new ArrayList<>() : options;
    }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : tags;
    }
}
