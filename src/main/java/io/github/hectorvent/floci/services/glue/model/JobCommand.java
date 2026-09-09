package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class JobCommand {
    @JsonProperty("Name")
    private String name;

    @JsonProperty("PythonVersion")
    private String pythonVersion;

    @JsonProperty("Runtime")
    private String runtime;

    @JsonProperty("ScriptLocation")
    private String scriptLocation;

    public JobCommand() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getPythonVersion() { return pythonVersion; }
    public void setPythonVersion(String pythonVersion) { this.pythonVersion = pythonVersion; }

    public String getRuntime() { return runtime; }
    public void setRuntime(String runtime) { this.runtime = runtime; }

    public String getScriptLocation() { return scriptLocation; }
    public void setScriptLocation(String scriptLocation) { this.scriptLocation = scriptLocation; }
}
