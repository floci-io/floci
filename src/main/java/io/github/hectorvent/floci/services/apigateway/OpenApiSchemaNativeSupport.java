package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.runtime.annotations.RegisterForReflection;
import io.swagger.models.Model;
import io.swagger.models.properties.Property;
import io.swagger.v3.oas.models.media.Schema;

/** Registers the model hierarchies Jackson reads and writes during OpenAPI import. */
@RegisterForReflection(targets = {Model.class, Property.class, Schema.class}, registerFullHierarchy = true)
public class OpenApiSchemaNativeSupport {}
