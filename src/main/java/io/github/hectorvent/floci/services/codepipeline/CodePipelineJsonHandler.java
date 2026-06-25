package io.github.hectorvent.floci.services.codepipeline;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

@ApplicationScoped
public class CodePipelineJsonHandler {
    private final CodePipelineService service;

    @Inject
    public CodePipelineJsonHandler(CodePipelineService service) {
        this.service = service;
    }

    public Response handle(String action, JsonNode request, String region, String account) {
        JsonNode response = service.handle(action, request, region, account);
        return Response.ok(response).build();
    }
}
