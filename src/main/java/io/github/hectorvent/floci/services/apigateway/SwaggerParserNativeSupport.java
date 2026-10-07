package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.runtime.annotations.RegisterForReflection;
import io.swagger.v3.parser.converter.SwaggerConverter;

/** Keeps the Swagger 2.0 parser provider available for OpenAPIParser's ServiceLoader. */
@RegisterForReflection(targets = SwaggerConverter.class, methods = false, fields = false, ignoreNested = true)
public class SwaggerParserNativeSupport {}
