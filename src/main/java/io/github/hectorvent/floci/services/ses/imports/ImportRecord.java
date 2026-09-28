package io.github.hectorvent.floci.services.ses.imports;

import io.github.hectorvent.floci.services.ses.model.TopicPreference;

import java.util.List;

/**
 * One parsed source record. {@code error} is set when the record could not be turned into an
 * import (it still counts as processed and failed, and the job carries on), mirroring how AWS
 * reports a partially failed import as COMPLETED with a FailedRecordsCount.
 */
public record ImportRecord(int line, String emailAddress, String reason, Boolean unsubscribeAll,
                    String attributesData, List<TopicPreference> topicPreferences, String error) {

    public static ImportRecord invalid(int line, String error) {
        return new ImportRecord(line, null, null, null, null, null, error);
    }
}
