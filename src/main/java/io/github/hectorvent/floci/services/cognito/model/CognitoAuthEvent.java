package io.github.hectorvent.floci.services.cognito.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/**
 * One sign-in attempt recorded for {@code AdminListUserAuthEvents}. The event belongs to the user's
 * {@code sub}, so a user deleted and created again under the same username starts with no history.
 * {@code sequence} orders events created in the same millisecond. Each challenge is a
 * {@code ChallengeName} ({@code Password} or {@code Mfa}) with its {@code ChallengeResponse}
 * ({@code Success} or {@code Failure}).
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class CognitoAuthEvent {

    /** One {@code ChallengeResponseType}. */
    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Challenge {
        private String challengeName;
        private String challengeResponse;

        public Challenge() {
        }

        public Challenge(String challengeName, String challengeResponse) {
            this.challengeName = challengeName;
            this.challengeResponse = challengeResponse;
        }

        public String getChallengeName() { return challengeName; }
        public void setChallengeName(String challengeName) { this.challengeName = challengeName; }

        public String getChallengeResponse() { return challengeResponse; }
        public void setChallengeResponse(String challengeResponse) { this.challengeResponse = challengeResponse; }
    }

    private String eventId;
    private String userPoolId;
    private String userSub;
    private String eventType;
    private String eventResponse;
    private long creationMillis;
    private long sequence;
    private List<Challenge> challengeResponses = new ArrayList<>();

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }

    public String getUserPoolId() { return userPoolId; }
    public void setUserPoolId(String userPoolId) { this.userPoolId = userPoolId; }

    public String getUserSub() { return userSub; }
    public void setUserSub(String userSub) { this.userSub = userSub; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public String getEventResponse() { return eventResponse; }
    public void setEventResponse(String eventResponse) { this.eventResponse = eventResponse; }

    public long getCreationMillis() { return creationMillis; }
    public void setCreationMillis(long creationMillis) { this.creationMillis = creationMillis; }

    public long getSequence() { return sequence; }
    public void setSequence(long sequence) { this.sequence = sequence; }

    public List<Challenge> getChallengeResponses() { return challengeResponses; }
    public void setChallengeResponses(List<Challenge> challengeResponses) {
        this.challengeResponses = challengeResponses;
    }
}
