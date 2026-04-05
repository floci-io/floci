package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Image {

    private String imageId;
    private String name;
    private String description;
    private String state = "available";
    private String ownerId = "amazon";
    private boolean isPublic = true;
    private String architecture;
    private String rootDeviceType = "ebs";
    private String rootDeviceName = "/dev/xvda";
    private String virtualizationType = "hvm";
    private String hypervisor = "xen";
    private String platform;
    private String imageOwnerAlias = "amazon";
    private String creationDate;
    private List<Tag> tags = new ArrayList<>();

    public Image() {}

    public String getImageId() { return imageId; }
    public void setImageId(String imageId) { this.imageId = imageId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    public boolean isPublic() { return isPublic; }
    public void setPublic(boolean aPublic) { isPublic = aPublic; }

    public String getArchitecture() { return architecture; }
    public void setArchitecture(String architecture) { this.architecture = architecture; }

    public String getRootDeviceType() { return rootDeviceType; }
    public void setRootDeviceType(String rootDeviceType) { this.rootDeviceType = rootDeviceType; }

    public String getRootDeviceName() { return rootDeviceName; }
    public void setRootDeviceName(String rootDeviceName) { this.rootDeviceName = rootDeviceName; }

    public String getVirtualizationType() { return virtualizationType; }
    public void setVirtualizationType(String virtualizationType) { this.virtualizationType = virtualizationType; }

    public String getHypervisor() { return hypervisor; }
    public void setHypervisor(String hypervisor) { this.hypervisor = hypervisor; }

    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }

    public String getImageOwnerAlias() { return imageOwnerAlias; }
    public void setImageOwnerAlias(String imageOwnerAlias) { this.imageOwnerAlias = imageOwnerAlias; }

    public String getCreationDate() { return creationDate; }
    public void setCreationDate(String creationDate) { this.creationDate = creationDate; }

    public List<Tag> getTags() { return tags; }
    public void setTags(List<Tag> tags) { this.tags = tags; }
}
