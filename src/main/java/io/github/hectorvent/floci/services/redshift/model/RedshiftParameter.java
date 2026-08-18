package io.github.hectorvent.floci.services.redshift.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class RedshiftParameter {

    private String parameterName;
    private String parameterValue;
    private String description;
    private String source;
    private String dataType;
    private String allowedValues;
    private String applyType;
    private boolean modifiable = true;
    private String minimumEngineVersion;

    public RedshiftParameter() {
    }

    public RedshiftParameter(String parameterName, String parameterValue, String description, String source,
                             String dataType, String allowedValues, String applyType) {
        this.parameterName = parameterName;
        this.parameterValue = parameterValue;
        this.description = description;
        this.source = source;
        this.dataType = dataType;
        this.allowedValues = allowedValues;
        this.applyType = applyType;
    }

    public String getParameterName() { return parameterName; }
    public void setParameterName(String parameterName) { this.parameterName = parameterName; }

    public String getParameterValue() { return parameterValue; }
    public void setParameterValue(String parameterValue) { this.parameterValue = parameterValue; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getDataType() { return dataType; }
    public void setDataType(String dataType) { this.dataType = dataType; }

    public String getAllowedValues() { return allowedValues; }
    public void setAllowedValues(String allowedValues) { this.allowedValues = allowedValues; }

    public String getApplyType() { return applyType; }
    public void setApplyType(String applyType) { this.applyType = applyType; }

    public boolean isModifiable() { return modifiable; }
    public void setModifiable(boolean modifiable) { this.modifiable = modifiable; }

    public String getMinimumEngineVersion() { return minimumEngineVersion; }
    public void setMinimumEngineVersion(String minimumEngineVersion) {
        this.minimumEngineVersion = minimumEngineVersion;
    }
}
