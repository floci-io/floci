public class FixtureSwitchHandler {
    public Object dispatch(String action) {
        return switch (action) {
            case "CreateSecret" -> handleCreateSecret();
            case "GetSecretValue" -> handleGetSecretValue();
            case "PutSecretValue" -> handlePutSecretValue();
            case "DeleteSecret" -> handleDeleteSecret();
            case "GetRandomPassword" -> handleGetRandomPassword();
            default -> null;
        };
    }

    private Status parseStatus(String s) {
        return switch (s.toUpperCase()) {
            case "DISABLED" -> Status.DISABLED;
            case "ENABLED" -> Status.ENABLED;
            default -> Status.UNKNOWN;
        };
    }
}
