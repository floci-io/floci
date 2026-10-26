package io.github.hectorvent.floci.services.emrserverless.model;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
public class RuntimeConfiguration {
    // Not named properties, as the other configurations' is: a configuration has a properties member,
    // which Jackson would bind to a field of that name, replacing every member read before it.
    private final Map<String, Object> members = new LinkedHashMap<>();

    @JsonAnyGetter
    public Map<String, Object> getMembers() {
        return members;
    }

    @JsonAnySetter
    public void setMember(String key, Object value) {
        members.put(key, value);
    }
}
