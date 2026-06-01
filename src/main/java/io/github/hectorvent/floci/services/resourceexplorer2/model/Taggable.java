package io.github.hectorvent.floci.services.resourceexplorer2.model;

import java.util.Map;

/** A resource carrying mutable tags. Implementations are immutable records; {@link #withTags} returns a copy. */
public interface Taggable {
    Map<String, String> tags();
    Taggable withTags(Map<String, String> tags);
}
