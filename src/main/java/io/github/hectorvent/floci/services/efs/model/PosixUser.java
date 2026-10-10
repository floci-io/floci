package io.github.hectorvent.floci.services.efs.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
@RegisterForReflection
public class PosixUser {

    private Long gid;
    private List<Long> secondaryGids;
    private Long uid;

    public Long getGid() {
        return gid;
    }

    public void setGid(Long gid) {
        this.gid = gid;
    }

    public List<Long> getSecondaryGids() {
        return secondaryGids;
    }

    public void setSecondaryGids(List<Long> secondaryGids) {
        this.secondaryGids = secondaryGids;
    }

    public Long getUid() {
        return uid;
    }

    public void setUid(Long uid) {
        this.uid = uid;
    }
}