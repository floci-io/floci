public class FixtureFirstHandler {
    public Object dispatch(String action) {
        return switch (action) {
            case "Publish" -> handlePublish();
            case "Subscribe" -> handleSubscribe();
            case "Unsubscribe" -> handleUnsubscribe();
            default -> null;
        };
    }
}
