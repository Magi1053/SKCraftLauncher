package com.skcraft.launcher.auth;

import java.io.IOException;

/**
 * Restores saved offline accounts without contacting authentication services.
 */
public class OfflineLoginService implements LoginService {

    @Override
    public Session restore(SavedSession savedSession)
            throws IOException, InterruptedException, AuthenticationException {
        return OfflineSession.fromSavedSession(savedSession);
    }
}
