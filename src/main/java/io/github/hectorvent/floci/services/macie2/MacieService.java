package io.github.hectorvent.floci.services.macie2;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.macie2.model.MacieState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;

@ApplicationScoped
public class MacieService implements Resettable {
    private final AccountAwareStorageBackend<MacieState> states;

    @Inject
    public MacieService(StorageFactory storageFactory) {
        this(storageFactory.create("macie2", "macie2-state.json",
                new TypeReference<Map<String, MacieState>>() {}));
    }

    MacieService(AccountAwareStorageBackend<MacieState> states) {
        this.states = states;
    }

    public MacieState state(String region) {
        return states.get(region).orElseGet(MacieState::new);
    }

    public synchronized void enableOrganizationAdminAccount(String region, String accountId) {
        requireAccountId(accountId);
        MacieState state = state(region);
        if (state.getAdminAccountId() != null && !state.getAdminAccountId().equals(accountId)) {
            throw conflict("A different Macie administrator account is already configured.");
        }
        state.setAdminAccountId(accountId);
        states.put(region, state);

        MacieState delegated = states.getForAccount(accountId, region).orElseGet(MacieState::new);
        delegated.setAdminAccountId(accountId);
        delegated.setEnabled(true);
        states.putForAccount(accountId, region, delegated);
    }

    public synchronized void enableMacie(String region) {
        MacieState state = state(region);
        if (state.isEnabled()) {
            throw conflict("Macie is already enabled for this account.");
        }
        state.setEnabled(true);
        states.put(region, state);
    }

    public MacieState requireSession(String region) {
        MacieState state = state(region);
        if (!state.isEnabled()) {
            throw notFound("Macie is not enabled for this account.");
        }
        return state;
    }

    public MacieState requireAdministratorSession(String region, String callerAccountId) {
        MacieState state = states.getForAccount(callerAccountId, region).orElseGet(MacieState::new);
        if (state.getAdminAccountId() == null && !state.isEnabled()) {
            throw notFound("Macie is not enabled for this account.");
        }
        if (!callerAccountId.equals(state.getAdminAccountId())) {
            throw accessDenied();
        }
        if (!state.isEnabled()) {
            throw notFound("Macie is not enabled for this account.");
        }
        return state;
    }

    public synchronized void updateOrganizationConfiguration(
            String region, String callerAccountId, boolean autoEnable) {
        MacieState state = requireAdministratorSession(region, callerAccountId);
        state.setAutoEnable(autoEnable);
        states.putForAccount(callerAccountId, region, state);
    }

    @Override
    public void clear() {
        states.clear();
    }

    private static void requireAccountId(String accountId) {
        if (accountId == null || !accountId.matches("\\d{12}")) {
            throw new AwsException("ValidationException", "adminAccountId must be a 12 digit account ID.", 400);
        }
    }

    private static AwsException conflict(String message) {
        return new AwsException("ConflictException", message, 409);
    }

    private static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }

    private static AwsException accessDenied() {
        return new AwsException("AccessDeniedException",
                "Only the delegated Macie administrator account can manage organization configuration.", 403);
    }
}
