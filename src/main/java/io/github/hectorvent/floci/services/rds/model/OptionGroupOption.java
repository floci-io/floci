package io.github.hectorvent.floci.services.rds.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A single option inside an {@link OptionGroup}, as added by {@code ModifyOptionGroup}
 * through {@code OptionsToInclude}.
 */
@RegisterForReflection
public class OptionGroupOption {

    private String optionName;
    private String optionDescription;
    private String optionVersion;
    private Integer port;
    private boolean persistent;
    private boolean permanent;
    private Map<String, String> optionSettings = new LinkedHashMap<>();
    private List<String> vpcSecurityGroupMemberships = new ArrayList<>();
    private List<String> dbSecurityGroupMemberships = new ArrayList<>();

    public OptionGroupOption() {}

    public OptionGroupOption(String optionName) {
        this.optionName = optionName;
    }

    public String getOptionName() { return optionName; }
    public void setOptionName(String optionName) { this.optionName = optionName; }

    public String getOptionDescription() { return optionDescription; }
    public void setOptionDescription(String optionDescription) { this.optionDescription = optionDescription; }

    public String getOptionVersion() { return optionVersion; }
    public void setOptionVersion(String optionVersion) { this.optionVersion = optionVersion; }

    public Integer getPort() { return port; }
    public void setPort(Integer port) { this.port = port; }

    public boolean isPersistent() { return persistent; }
    public void setPersistent(boolean persistent) { this.persistent = persistent; }

    public boolean isPermanent() { return permanent; }
    public void setPermanent(boolean permanent) { this.permanent = permanent; }

    public Map<String, String> getOptionSettings() { return optionSettings; }
    public void setOptionSettings(Map<String, String> optionSettings) {
        this.optionSettings = optionSettings == null ? new LinkedHashMap<>() : optionSettings;
    }

    public List<String> getVpcSecurityGroupMemberships() { return vpcSecurityGroupMemberships; }
    public void setVpcSecurityGroupMemberships(List<String> vpcSecurityGroupMemberships) {
        this.vpcSecurityGroupMemberships =
                vpcSecurityGroupMemberships == null ? new ArrayList<>() : vpcSecurityGroupMemberships;
    }

    public List<String> getDbSecurityGroupMemberships() { return dbSecurityGroupMemberships; }
    public void setDbSecurityGroupMemberships(List<String> dbSecurityGroupMemberships) {
        this.dbSecurityGroupMemberships =
                dbSecurityGroupMemberships == null ? new ArrayList<>() : dbSecurityGroupMemberships;
    }
}
