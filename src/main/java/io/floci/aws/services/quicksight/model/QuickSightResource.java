package io.floci.aws.services.quicksight.model;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record QuickSightResource(ObjectNode document) {}
