package io.floci.aws.services.cloudwatch.metrics;

import com.google.protobuf.Any;
import com.google.rpc.Status;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** Registers protobuf message accessors used reflectively by protobuf's JSON codec. */
@RegisterForReflection(targets = {Any.class, Status.class})
public class OtlpMetricsNativeSupport {}
