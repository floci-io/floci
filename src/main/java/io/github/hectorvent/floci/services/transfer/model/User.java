package io.github.hectorvent.floci.services.transfer.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

@RegisterForReflection
public class User {

    private String userName;
    private String arn;
    private String homeDirectory;
    private String homeDirectoryType;
    private List<HomeDirectoryMapping> homeDirectoryMappings;
    private String role;
    private List<SshPublicKey> sshPublicKeys;
    private Map<String, String> tags;

    public User() {}

    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getHomeDirectory() { return homeDirectory; }
    public void setHomeDirectory(String homeDirectory) { this.homeDirectory = homeDirectory; }

    public String getHomeDirectoryType() { return homeDirectoryType; }
    public void setHomeDirectoryType(String homeDirectoryType) { this.homeDirectoryType = homeDirectoryType; }

    public List<HomeDirectoryMapping> getHomeDirectoryMappings() { return homeDirectoryMappings; }
    public void setHomeDirectoryMappings(List<HomeDirectoryMapping> homeDirectoryMappings) { this.homeDirectoryMappings = homeDirectoryMappings; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public List<SshPublicKey> getSshPublicKeys() { return sshPublicKeys; }
    public void setSshPublicKeys(List<SshPublicKey> sshPublicKeys) { this.sshPublicKeys = sshPublicKeys; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
