package com.skcraft.launcher.installer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class AzureWindowsSigningConfig {
    public static final String ENDPOINT_VARIABLE = "WINDOWS_SIGN_AZURE_ENDPOINT";
    public static final String ACCOUNT_VARIABLE = "WINDOWS_SIGN_AZURE_ACCOUNT";
    public static final String PROFILE_VARIABLE = "WINDOWS_SIGN_AZURE_PROFILE";
    public static final String TOKEN_VARIABLE = "WINDOWS_SIGN_AZURE_TOKEN";

    private static final String[] REQUIRED_VARIABLES = {
            ENDPOINT_VARIABLE,
            ACCOUNT_VARIABLE,
            PROFILE_VARIABLE
    };

    private final String endpointHost;
    private final String account;
    private final String profile;
    private final String tokenOverride;

    private AzureWindowsSigningConfig(String endpointHost, String account, String profile, String tokenOverride) {
        this.endpointHost = endpointHost;
        this.account = account;
        this.profile = profile;
        this.tokenOverride = tokenOverride;
    }

    public static AzureWindowsSigningConfig fromEnvironment(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        String endpoint = trimToNull(environment.get(ENDPOINT_VARIABLE));
        String account = trimToNull(environment.get(ACCOUNT_VARIABLE));
        String profile = trimToNull(environment.get(PROFILE_VARIABLE));
        String token = trimToNull(environment.get(TOKEN_VARIABLE));
        String[] values = {endpoint, account, profile};

        List<String> missing = new ArrayList<>();
        for (int i = 0; i < REQUIRED_VARIABLES.length; i++) {
            if (values[i] == null) {
                missing.add(REQUIRED_VARIABLES[i]);
            }
        }

        if (missing.size() == REQUIRED_VARIABLES.length) {
            if (token != null) {
                throw new IllegalArgumentException(
                        TOKEN_VARIABLE + " is set, but " + ENDPOINT_VARIABLE + ", "
                                + ACCOUNT_VARIABLE + ", and " + PROFILE_VARIABLE + " are also required.");
            }
            return null;
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    "Windows Artifact Signing requires " + ENDPOINT_VARIABLE + ", "
                            + ACCOUNT_VARIABLE + ", and " + PROFILE_VARIABLE
                            + " together. Missing: " + String.join(", ", missing) + ".");
        }

        return new AzureWindowsSigningConfig(normalizeEndpointHost(endpoint), account, profile, token);
    }

    public String endpointHost() {
        return endpointHost;
    }

    public String account() {
        return account;
    }

    public String profile() {
        return profile;
    }

    public String alias() {
        return account + "/" + profile;
    }

    public String tokenOverride() {
        return tokenOverride;
    }

    private static String normalizeEndpointHost(String endpoint) {
        String value = endpoint.trim();
        if (value.regionMatches(true, 0, "https://", 0, 8)) {
            value = value.substring(8);
        } else if (value.regionMatches(true, 0, "http://", 0, 7)) {
            value = value.substring(7);
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.isEmpty()) {
            throw new IllegalArgumentException(ENDPOINT_VARIABLE + " must not be blank.");
        }
        return value;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
