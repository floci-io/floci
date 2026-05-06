public class FixtureSecondHandler {
    public Object dispatch(String action) {
        return switch (action) {
            case "Publish" -> handlePublishLegacy();
            case "ListTopics" -> handleListTopics();
            case "CreateTopic" -> handleCreateTopic();
            default -> null;
        };
    }
}
