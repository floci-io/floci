package io.github.hectorvent.floci.services.transcribe.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VocabularyInfo(
        @JsonProperty("VocabularyName") String vocabularyName,
        @JsonProperty("LanguageCode") String languageCode,
        @JsonProperty("VocabularyState") String vocabularyState,
        @JsonProperty("LastModifiedTime") Long lastModifiedTime
) {}
