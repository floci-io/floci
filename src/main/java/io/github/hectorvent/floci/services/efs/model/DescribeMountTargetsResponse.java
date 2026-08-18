package io.github.hectorvent.floci.services.efs.model;

import java.util.List;

public class DescribeMountTargetsResponse {

    private String marker;
    private List<MountTarget> mountTargets;
    private String nextMarker;

    public String getMarker() {
        return marker;
    }

    public void setMarker(String marker) {
        this.marker = marker;
    }

    public List<MountTarget> getMountTargets() {
        return mountTargets;
    }

    public void setMountTargets(List<MountTarget> mountTargets) {
        this.mountTargets = mountTargets;
    }

    public String getNextMarker() {
        return nextMarker;
    }

    public void setNextMarker(String nextMarker) {
        this.nextMarker = nextMarker;
    }
}