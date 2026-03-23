package io.github.hectorvent.floci.services.acm.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
public record DomainValidation(
    String domainName,
    String validationDomain,
    String validationStatus,
    String validationMethod,
    ResourceRecord resourceRecord,
    List<String> validationEmails
) {}
