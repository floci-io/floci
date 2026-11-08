package io.floci.aws.services.s3.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class WebsiteConfiguration {
    private String indexDocument;
    private String errorDocument;

    public WebsiteConfiguration() {}

    public WebsiteConfiguration(String indexDocument, String errorDocument) {
        this.indexDocument = indexDocument;
        this.errorDocument = errorDocument;
    }

    public String getIndexDocument() { return indexDocument; }
    public void setIndexDocument(String indexDocument) { this.indexDocument = indexDocument; }

    public String getErrorDocument() { return errorDocument; }
    public void setErrorDocument(String errorDocument) { this.errorDocument = errorDocument; }
}
