package com.skcraft.launcher.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.skcraft.launcher.auth.microsoft.MicrosoftWebAuthorizer;
import com.skcraft.launcher.auth.microsoft.MinecraftServicesAuthorizer;
import com.skcraft.launcher.auth.microsoft.OauthResult;
import com.skcraft.launcher.auth.microsoft.XboxTokenAuthorizer;
import com.skcraft.launcher.auth.microsoft.model.McAuthResponse;
import com.skcraft.launcher.auth.microsoft.model.McProfileResponse;
import com.skcraft.launcher.auth.microsoft.model.TokenResponse;
import com.skcraft.launcher.auth.microsoft.model.XboxAuthorization;
import com.skcraft.launcher.auth.skin.MinecraftSkinService;
import com.skcraft.launcher.util.HttpRequest;
import lombok.Data;
import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.Map;
import java.util.function.Consumer;

import static com.skcraft.launcher.util.HttpRequest.url;

@RequiredArgsConstructor
public class MicrosoftLoginService implements LoginService {
	private static final URL MS_TOKEN_URL = url("https://login.live.com/oauth20_token.srf");
	private static final URL MS_DEVICE_CODE_URL = url("https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode");
	private static final URL MS_DEVICE_TOKEN_URL = url("https://login.microsoftonline.com/consumers/oauth2/v2.0/token");
	private static final String MS_SCOPE = "XboxLive.signin XboxLive.offline_access";

	private final String clientId;

	/**
	 * Trigger a full login sequence with the Microsoft authenticator.
	 *
	 * @param oauthDone Callback called when OAuth is complete and automatic login is about to begin.
	 * @return Valid {@link Session} instance representing the logged-in player.
	 * @throws IOException if any I/O error occurs.
	 * @throws InterruptedException if the current thread is interrupted
	 * @throws AuthenticationException if authentication fails in any way, this is thrown with a human-useful message.
	 */
	public Session login(Receiver oauthDone) throws IOException, InterruptedException, AuthenticationException {
		MicrosoftWebAuthorizer authorizer = new MicrosoftWebAuthorizer(clientId);
		OauthResult auth = authorizer.authorize();

		if (auth.isError()) {
			OauthResult.Error error = (OauthResult.Error) auth;
			throw new AuthenticationException(error.getErrorMessage());
		}

		TokenResponse response = exchangeToken(form -> {
			form.add("grant_type", "authorization_code");
			form.add("redirect_uri", authorizer.getRedirectUri());
			form.add("code", ((OauthResult.Success) auth).getAuthCode());
		});

		oauthDone.tell();
		Profile session = performLogin(response.getAccessToken(), null);
		session.setRefreshToken(response.getRefreshToken());

		return session;
	}

	public DeviceCodeDetails requestDeviceCodeDetails()
			throws IOException, InterruptedException, AuthenticationException {
		DeviceCodeResponse deviceCode = requestDeviceCode();
		if (deviceCode.getDeviceCode() == null || deviceCode.getDeviceCode().isEmpty()) {
			throw new AuthenticationException("Failed to obtain Microsoft device code.");
		}

		long expiresAt = System.currentTimeMillis() + (deviceCode.getExpiresIn() * 1000L);
		return new DeviceCodeDetails(
				deviceCode.getDeviceCode(),
				deviceCode.getUserCode(),
				deviceCode.getVerificationUri(),
				deviceCode.getVerificationUriComplete(),
				deviceCode.getMessage(),
				expiresAt,
				deviceCode.getInterval());
	}

	public Session loginWithDeviceCode(DeviceCodeDetails details, Receiver oauthDone)
			throws IOException, InterruptedException, AuthenticationException {
		TokenResponse response = pollDeviceCodeToken(details);
		oauthDone.tell();

		Profile session = performLogin(response.getAccessToken(), null);
		session.setRefreshToken(response.getRefreshToken());
		return session;
	}

	@Override
	public Session restore(SavedSession savedSession)
			throws IOException, InterruptedException, AuthenticationException {
		TokenResponse response = exchangeToken(form -> {
			form.add("grant_type", "refresh_token");
			form.add("refresh_token", savedSession.getRefreshToken());
		});

		Profile session = performLogin(response.getAccessToken(), savedSession);
		session.setRefreshToken(response.getRefreshToken());

		return session;
	}

	private TokenResponse exchangeToken(Consumer<HttpRequest.Form> formConsumer)
			throws IOException, InterruptedException, AuthenticationException {
		HttpRequest.Form form = HttpRequest.Form.form();
		form.add("client_id", clientId);
		formConsumer.accept(form);

		return HttpRequest.post(MS_TOKEN_URL)
				.bodyForm(form)
				.execute()
				.expectResponseCodeOr(200, (req) -> {
					TokenError error = req.returnContent().asJson(TokenError.class);

					return new AuthenticationException(error.errorDescription, true);
				})
				.returnContent()
				.asJson(TokenResponse.class);
	}

	private DeviceCodeResponse requestDeviceCode() throws IOException, InterruptedException, AuthenticationException {
		HttpRequest.Form form = HttpRequest.Form.form();
		form.add("client_id", clientId);
		form.add("scope", MS_SCOPE);

		return HttpRequest.post(MS_DEVICE_CODE_URL)
				.bodyForm(form)
				.execute()
				.expectResponseCodeOr(200, (req) -> {
					TokenError error = req.returnContent().asJson(TokenError.class);
					return new AuthenticationException(error.errorDescription, true);
				})
				.returnContent()
				.asJson(DeviceCodeResponse.class);
	}

	private TokenResponse pollDeviceCodeToken(DeviceCodeDetails details)
			throws IOException, InterruptedException, AuthenticationException {
		int intervalSeconds = Math.max(1, details.getInterval());
		long expiryTime = details.getExpiresAt();

		while (System.currentTimeMillis() < expiryTime) {
			DeviceCodeTokenResult result = exchangeDeviceCodeToken(details.getDeviceCode());
			if (result.success != null) {
				return result.success;
			}

			String errorCode = result.error != null ? result.error.error : null;
			if ("authorization_pending".equals(errorCode)) {
				Thread.sleep(intervalSeconds * 1000L);
				continue;
			}
			if ("slow_down".equals(errorCode)) {
				intervalSeconds++;
				Thread.sleep(intervalSeconds * 1000L);
				continue;
			}
			if ("expired_token".equals(errorCode) || "invalid_grant".equals(errorCode)) {
				throw new AuthenticationException("Device code expired. Please retry sign in.");
			}
			if ("authorization_declined".equals(errorCode) || "access_denied".equals(errorCode)) {
				throw new AuthenticationException("Microsoft sign in was cancelled.");
			}

			String message = result.error != null && result.error.errorDescription != null
					? result.error.errorDescription : "Microsoft device sign in failed.";
			throw new AuthenticationException(message, true);
		}

		throw new AuthenticationException("Timed out waiting for Microsoft sign in.");
	}

	private DeviceCodeTokenResult exchangeDeviceCodeToken(String deviceCode)
			throws IOException, InterruptedException {
		HttpRequest.Form form = HttpRequest.Form.form();
		form.add("client_id", clientId);
		form.add("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
		form.add("device_code", deviceCode);
		form.add("scope", MS_SCOPE);

		HttpRequest request = HttpRequest.post(MS_DEVICE_TOKEN_URL)
				.bodyForm(form)
				.execute();

		try {
			if (request.getResponseCode() == 200) {
				return new DeviceCodeTokenResult(request.returnContent().asJson(TokenResponse.class), null);
			}

			TokenError error = request.returnContent().asJson(TokenError.class);
			return new DeviceCodeTokenResult(null, error);
		} finally {
			request.close();
		}
	}

	private Profile performLogin(String microsoftToken, SavedSession previous)
			throws IOException, InterruptedException, AuthenticationException {
		XboxAuthorization xboxAuthorization = XboxTokenAuthorizer.authorizeWithXbox(microsoftToken);
		McAuthResponse auth = MinecraftServicesAuthorizer.authorizeWithMinecraft(xboxAuthorization);
		McProfileResponse profile = MinecraftServicesAuthorizer.getUserProfile(auth);

		Profile session = new Profile(auth, profile);
		if (previous != null && previous.getAvatarImage() != null) {
			session.setAvatarImage(previous.getAvatarImage());
		} else {
			session.setAvatarImage(MinecraftSkinService.fetchSkinHead(profile));
		}

		return session;
	}

	@Data
	public static class Profile implements Session {
		private final McAuthResponse auth;
		private final McProfileResponse profile;
		private final Map<String, String> userProperties = Collections.emptyMap();
		private String refreshToken;
		private byte[] avatarImage;

		@Override
		public String getUuid() {
			return profile.getUuid();
		}

		@Override
		public String getName() {
			return profile.getName();
		}

		@Override
		public String getAccessToken() {
			return auth.getAccessToken();
		}

		@Override
		public String getSessionToken() {
			return String.format("token:%s:%s", getAccessToken(), getUuid());
		}

		@Override
		public UserType getUserType() {
			return UserType.MICROSOFT;
		}

		@Override
		public boolean isOnline() {
			return true;
		}

		@Override
		public SavedSession toSavedSession() {
			SavedSession savedSession = new SavedSession();

			savedSession.setType(getUserType());
			savedSession.setUsername(getName());
			savedSession.setUuid(getUuid());
			savedSession.setAccessToken(getAccessToken());
			savedSession.setRefreshToken(getRefreshToken());
			savedSession.setAvatarImage(getAvatarImage());

			return savedSession;
		}
	}

	@Data
	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	@JsonIgnoreProperties(ignoreUnknown = true)
	private static class TokenError {
		private String error;
		private String errorDescription;
	}

	@Data
	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	@JsonIgnoreProperties(ignoreUnknown = true)
	private static class DeviceCodeResponse {
		private String deviceCode;
		private String userCode;
		private String verificationUri;
		private String verificationUriComplete;
		private Integer expiresIn;
		private Integer interval;
		private String message;

		public int getExpiresIn() {
			return expiresIn != null ? expiresIn : 900;
		}

		public int getInterval() {
			return interval != null ? interval : 5;
		}
	}

	@RequiredArgsConstructor
	private static class DeviceCodeTokenResult {
		private final TokenResponse success;
		private final TokenError error;
	}

	@Data
	@RequiredArgsConstructor
	public static class DeviceCodeDetails {
		private final String deviceCode;
		private final String userCode;
		private final String verificationUri;
		private final String verificationUriComplete;
		private final String message;
		private final long expiresAt;
		private final int interval;
	}

	@FunctionalInterface
	public interface Receiver {
		void tell();
	}

}
