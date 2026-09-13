package io.github.hectorvent.floci.services.datasync.model;

import java.util.Map;

/**
 * A DataSync resource addressable by the service's own {@code TagResource},
 * {@code UntagResource} and {@code ListTagsForResource} operations.
 */
public interface DataSyncTaggable {

    Map<String, String> getTags();

    void setTags(Map<String, String> tags);
}
