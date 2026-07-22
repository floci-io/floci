package io.github.hectorvent.floci.services.lakeformation.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

public class CreateTableDefaultPermission {
    @JsonProperty("Principal")
    private Principal principal;

    @JsonProperty("Permissions")
    private List<String> permissions = new ArrayList<>();

    @JsonProperty("PermissionsWithGrantOption")
    private List<String> permissionsWithGrantOption = new ArrayList<>();

    public Principal getPrincipal() {
        return principal;
    }

    public void setPrincipal(Principal principal) {
        this.principal = principal;
    }

    public List<String> getPermissions() {
        return permissions;
    }

    public void setPermissions(List<String> permissions) {
        this.permissions = permissions == null ? new ArrayList<>() : permissions;
    }

    public List<String> getPermissionsWithGrantOption() {
        return permissionsWithGrantOption;
    }

    public void setPermissionsWithGrantOption(List<String> permissionsWithGrantOption) {
        this.permissionsWithGrantOption = permissionsWithGrantOption == null ? new ArrayList<>() : permissionsWithGrantOption;
    }
}
