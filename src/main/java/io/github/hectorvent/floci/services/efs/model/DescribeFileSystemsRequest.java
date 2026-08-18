public class DescribeFileSystemsRequest {

    private String creationToken;
    private String fileSystemId;
    private String marker;
    private Integer maxItems;

    public String getCreationToken() {
        return creationToken;
    }

    public void setCreationToken(String creationToken) {
        this.creationToken = creationToken;
    }

    public String getFileSystemId() {
        return fileSystemId;
    }

    public void setFileSystemId(String fileSystemId) {
        this.fileSystemId = fileSystemId;
    }

    public String getMarker() {
        return marker;
    }

    public void setMarker(String marker) {
        this.marker = marker;
    }

    public Integer getMaxItems() {
        return maxItems;
    }

    public void setMaxItems(Integer maxItems) {
        this.maxItems = maxItems;
    }
}