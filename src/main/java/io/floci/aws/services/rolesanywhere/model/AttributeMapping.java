package io.floci.aws.services.rolesanywhere.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/**
 * The mapping rules a profile applies to one field of the authenticating certificate: the field
 * ({@code x509Subject}, {@code x509Issuer} or {@code x509SAN}) and the specifiers of it that become session tags.
 */
@RegisterForReflection
public class AttributeMapping {

    private String certificateField;
    private List<String> specifiers = new ArrayList<>();

    public AttributeMapping() {
    }

    public AttributeMapping(String certificateField, List<String> specifiers) {
        this.certificateField = certificateField;
        this.specifiers = new ArrayList<>(specifiers);
    }

    public String getCertificateField() {
        return certificateField;
    }

    public void setCertificateField(String certificateField) {
        this.certificateField = certificateField;
    }

    public List<String> getSpecifiers() {
        return specifiers;
    }

    public void setSpecifiers(List<String> specifiers) {
        this.specifiers = specifiers == null ? new ArrayList<>() : new ArrayList<>(specifiers);
    }
}
