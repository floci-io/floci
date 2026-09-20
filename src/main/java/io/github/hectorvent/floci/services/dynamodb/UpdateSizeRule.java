package io.github.hectorvent.floci.services.dynamodb;

/** How a surface measures an update against the 400KB item limit. */
enum UpdateSizeRule {

    UPDATE_ITEM,
    FINISHED_ITEM
}
