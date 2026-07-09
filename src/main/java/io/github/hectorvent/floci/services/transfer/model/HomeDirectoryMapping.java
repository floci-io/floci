package io.github.hectorvent.floci.services.transfer.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class HomeDirectoryMapping {

    private String entry;
    private String target;

    public HomeDirectoryMapping() {}

    public HomeDirectoryMapping(String entry, String target) {
        this.entry = entry;
        this.target = target;
    }

    public String getEntry() { return entry; }
    public void setEntry(String entry) { this.entry = entry; }

    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }
}
