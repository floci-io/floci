package io.github.hectorvent.floci.services.glue;

import java.time.Instant;

/**
 * One finished job run or crawl, as a conditional trigger sees it. {@code position} orders the
 * completions of one job or crawler; a trigger remembers the last position it has processed.
 * {@code originRunId} is the run that set off the trigger chain this run belongs to (the run itself
 * when no trigger started it): a job run id, or a crawl id beginning {@code crawl:}. {@code workflowRunId}
 * is the workflow run the run or crawl belongs to, or null.
 */
public record GlueRunCompletion(String position, Instant finishedAt, String state, String originRunId,
                                String workflowRunId) {}
