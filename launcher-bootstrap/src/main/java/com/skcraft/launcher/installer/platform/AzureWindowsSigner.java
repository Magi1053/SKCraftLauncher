package com.skcraft.launcher.installer.platform;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import com.azure.identity.AzureCliCredentialBuilder;
import com.azure.identity.ChainedTokenCredentialBuilder;
import com.azure.identity.EnvironmentCredentialBuilder;
import com.skcraft.launcher.installer.AzureWindowsSigningConfig;
import net.jsign.AuthenticodeSigner;
import net.jsign.DigestAlgorithm;
import net.jsign.KeyStoreBuilder;
import net.jsign.pe.PEFile;
import net.jsign.timestamp.TimestampingMode;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;

final class AzureWindowsSigner {
    private static final String CODE_SIGNING_SCOPE = "https://codesigning.azure.net/.default";
    private static final String TIMESTAMP_URL = "http://timestamp.acs.microsoft.com";

    private AzureWindowsSigner() {
    }

    static String resolveToken(AzureWindowsSigningConfig config) {
        if (config.tokenOverride() != null) {
            return config.tokenOverride();
        }

        try {
            // Environment + Azure CLI: az login / service principal, without managed-identity timeouts.
            TokenCredential credential = new ChainedTokenCredentialBuilder()
                    .addLast(new EnvironmentCredentialBuilder().build())
                    .addLast(new AzureCliCredentialBuilder().build())
                    .build();
            TokenRequestContext request = new TokenRequestContext().addScopes(CODE_SIGNING_SCOPE);
            AccessToken accessToken = credential.getTokenSync(request);
            if (accessToken == null || accessToken.getToken() == null || accessToken.getToken().isBlank()) {
                throw new IllegalStateException(tokenFailureMessage());
            }
            return accessToken.getToken();
        } catch (IllegalStateException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalStateException(tokenFailureMessage(), e);
        }
    }

    static void sign(Path file, String programName, AzureWindowsSigningConfig config, String token)
            throws Exception {
        try {
            ensureWritable(file);
            KeyStore keyStore = new KeyStoreBuilder()
                    .storetype("TRUSTEDSIGNING")
                    .keystore(config.endpointHost())
                    .storepass(token)
                    .build();
            AuthenticodeSigner signer = new AuthenticodeSigner(keyStore, config.alias(), token)
                    .withProgramName(programName)
                    .withDigestAlgorithm(DigestAlgorithm.SHA256)
                    .withSignaturesReplaced(true)
                    .withTimestamping(true)
                    .withTimestampingMode(TimestampingMode.RFC3161)
                    .withTimestampingAuthority(TIMESTAMP_URL);

            try (PEFile peFile = new PEFile(file.toFile())) {
                signer.sign(peFile);
            }
            try (PEFile signedFile = new PEFile(file.toFile())) {
                if (signedFile.getSignatures().isEmpty()) {
                    throw new IllegalStateException("Authenticode signature was not written to " + file);
                }
            }
        } catch (AccessDeniedException e) {
            throw new IllegalStateException(
                    "Azure Artifact Signing could not write " + file
                            + ". Close the running launcher if it is open, then retry.",
                    e);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Azure Artifact Signing failed for " + file + " (" + config.alias()
                            + " at " + config.endpointHost() + ").",
                    e);
        }
        System.out.println("Authenticode-signed " + file);
    }

    private static void ensureWritable(Path file) throws IOException {
        try {
            Files.setAttribute(file, "dos:readonly", false);
        } catch (UnsupportedOperationException ignored) {
            // Non-DOS filesystems have no read-only attribute.
        }
        file.toFile().setWritable(true);
    }

    private static String tokenFailureMessage() {
        return "Azure Artifact Signing could not obtain an access token. Run az login, or set "
                + AzureWindowsSigningConfig.TOKEN_VARIABLE + ".";
    }
}
