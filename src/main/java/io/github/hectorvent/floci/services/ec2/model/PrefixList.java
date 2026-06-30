package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class PrefixList {

    private String prefixListId;
    private String prefixListName;
    private List<String> cidrs = new ArrayList<>();

    public PrefixList() {}

    public PrefixList(String prefixListId, String prefixListName, List<String> cidrs) {
        this.prefixListId = prefixListId;
        this.prefixListName = prefixListName;
        this.cidrs = cidrs;
    }

    public String getPrefixListId() { return prefixListId; }
    public void setPrefixListId(String prefixListId) { this.prefixListId = prefixListId; }

    public String getPrefixListName() { return prefixListName; }
    public void setPrefixListName(String prefixListName) { this.prefixListName = prefixListName; }

    public List<String> getCidrs() { return cidrs; }
    public void setCidrs(List<String> cidrs) { this.cidrs = cidrs; }
}
