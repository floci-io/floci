package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.services.ses.model.Tag;

import java.util.List;

/**
 * A domain whose resources the V2 tag endpoints address by ARN. Each method resolves the resource
 * in {@code region} and fails with that domain's {@code NotFoundException} when it is missing.
 */
interface SesTaggable {

    List<Tag> listTags(String name, String region);

    void tag(String name, String region, List<Tag> newTags);

    void untag(String name, String region, List<String> tagKeys);
}
