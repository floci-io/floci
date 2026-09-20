package io.github.hectorvent.floci.services.codeartifact.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class PackageAsset {
    private String name;
    private long size;
    // Kept out of the persisted package-version JSON/WAL: CodeArtifactService writes and reads
    // these bytes through its own asset store (disk file in persistent/hybrid/wal mode, a side
    // map in memory mode) instead, so a publish never rewrites every asset of every package
    // version just to append one more.
    @JsonIgnore
    private byte[] content = new byte[0];
    private Map<String, String> hashes = new LinkedHashMap<>();

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public byte[] getContent() {
        return content.clone();
    }

    public void setContent(byte[] content) {
        this.content = content == null ? new byte[0] : content.clone();
    }

    public Map<String, String> getHashes() {
        return Collections.unmodifiableMap(hashes);
    }

    public void setHashes(Map<String, String> hashes) {
        this.hashes = hashes == null ? new LinkedHashMap<>() : new LinkedHashMap<>(hashes);
    }
}
