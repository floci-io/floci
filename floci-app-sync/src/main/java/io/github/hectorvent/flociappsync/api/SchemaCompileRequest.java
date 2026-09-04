package io.github.hectorvent.flociappsync.api;

/** Body of {@code POST /schemas/{apiId}} — backs Floci's {@code StartSchemaCreation}. */
public record SchemaCompileRequest(String sdl) {
}
