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
}
