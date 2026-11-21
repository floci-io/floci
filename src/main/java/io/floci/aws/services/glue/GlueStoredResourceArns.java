package io.floci.aws.services.glue;

import io.floci.aws.core.common.StoredResourceArns;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** What IAM asks Glue of the resources a request names: the tags of the resource an ARN names. */
@ApplicationScoped
public class GlueStoredResourceArns implements StoredResourceArns {

    private final GlueService glue;

    @Inject
    public GlueStoredResourceArns(GlueService glue) {
        this.glue = glue;
    }

    @Override
    public String namespace() {
        return "glue";
    }

    /** Glue mints no id into an ARN a request names by its name, so there is nothing to find. */
    @Override
    public Optional<String> find(String type, List<String> names, String region) {
        return Optional.empty();
    }

    @Override
    public Map<String, String> storedTags(String arn) {
        return glue.storedTags(arn);
    }
}
