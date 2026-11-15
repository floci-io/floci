package io.floci.aws.services.xray.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/**
 * One trace, as the segments a client sent for it.
 *
 * <p>The documents are kept verbatim: X-Ray's API hands a segment back exactly as it was put, and
 * the SDK parses it, so re-serialising through a model here would change what the caller reads.
 * What Floci parses out of them is only what it has to index, which lives on {@link SegmentSummary}.
 */
@RegisterForReflection
public class Trace {

    private String id;
    private List<String> documents = new ArrayList<>();
    /** Epoch seconds, from the documents, for the retention window and the summaries. */
    private double startTime;
    private double endTime;
    private boolean fault;
    private boolean error;
    private boolean throttle;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public List<String> getDocuments() {
        return documents;
    }

    public void setDocuments(List<String> documents) {
        this.documents = documents == null ? new ArrayList<>() : documents;
    }

    public double getStartTime() {
        return startTime;
    }

    public void setStartTime(double startTime) {
        this.startTime = startTime;
    }

    public double getEndTime() {
        return endTime;
    }

    public void setEndTime(double endTime) {
        this.endTime = endTime;
    }

    public boolean isFault() {
        return fault;
    }

    public void setFault(boolean fault) {
        this.fault = fault;
    }

    public boolean isError() {
        return error;
    }

    public void setError(boolean error) {
        this.error = error;
    }

    public boolean isThrottle() {
        return throttle;
    }

    public void setThrottle(boolean throttle) {
        this.throttle = throttle;
    }
}
