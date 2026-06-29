package io.github.hectorvent.floci.services.elasticbeanstalk.model;

public class ConfigurationOptionSetting {

    private String namespace;
    private String optionName;
    private String resourceName;
    private String value;

    public ConfigurationOptionSetting() {
    }

    public ConfigurationOptionSetting(String namespace, String optionName, String resourceName, String value) {
        this.namespace = namespace;
        this.optionName = optionName;
        this.resourceName = resourceName;
        this.value = value;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getOptionName() {
        return optionName;
    }

    public void setOptionName(String optionName) {
        this.optionName = optionName;
    }

    public String getResourceName() {
        return resourceName;
    }

    public void setResourceName(String resourceName) {
        this.resourceName = resourceName;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }
}
