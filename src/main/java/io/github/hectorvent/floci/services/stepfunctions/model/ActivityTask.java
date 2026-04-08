package io.github.hectorvent.floci.services.stepfunctions.model;

/**
 * In-memory only — not persisted. Represents a task queued for an activity worker.
 */
public class ActivityTask {
    private final String taskToken;
    private final String input;

    public ActivityTask(String taskToken, String input) {
        this.taskToken = taskToken;
        this.input = input;
    }

    public String getTaskToken() { return taskToken; }
    public String getInput() { return input; }
}
