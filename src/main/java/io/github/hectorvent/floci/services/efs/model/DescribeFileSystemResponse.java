package io.github.hectorvent.floci.services.efs.model;
import java.util.List;

public class DescribeFileSystemResponse {

    private List<FileSystem> fileSystems;
    private String marker;
    private String nextMarker;

    public List<FileSystem> getFileSystems() {
        return fileSystems;
    }

    public void setFileSystems(List<FileSystem> fileSystems) {
        this.fileSystems = fileSystems;
    }

    public String getMarker() {
        return marker;
    }

    public void setMarker(String marker) {
        this.marker = marker;
    }

    public String getNextMarker() {
        return nextMarker;
    }

    public void setNextMarker(String nextMarker) {
        this.nextMarker = nextMarker;
    }
}