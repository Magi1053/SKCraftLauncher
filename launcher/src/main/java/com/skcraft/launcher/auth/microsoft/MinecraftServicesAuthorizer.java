package com.skcraft.launcher.auth.microsoft;

import com.skcraft.launcher.auth.AuthenticationException;
import com.skcraft.launcher.auth.microsoft.model.*;
import com.skcraft.launcher.util.HttpRequest;
import com.skcraft.launcher.util.SharedLocale;

import java.io.IOException;
import java.net.URL;

import static com.skcraft.launcher.util.HttpRequest.url;

public class MinecraftServicesAuthorizer {
	private static final URL MC_SERVICES_LOGIN = url("https://api.minecraftservices.com/authentication/login_with_xbox");
	private static final URL MC_SERVICES_PROFILE = url("https://api.minecraftservices.com/minecraft/profile");

	public static McAuthResponse authorizeWithMinecraft(XboxAuthorization auth) throws IOException, InterruptedException, AuthenticationException {
		McAuthRequest request = new McAuthRequest("XBL3.0 x=" + auth.getCombinedToken());

		return HttpRequest.post(MC_SERVICES_LOGIN)
				.bodyJson(request)
				.header("Accept", "application/json")
				.execute()
				.expectResponseCodeOr(200, req -> {
					int responseCode = req.getResponseCode();
					return new AuthenticationException("Minecraft services login failed with HTTP " + responseCode,
							SharedLocale.tr("login.minecraft.error", "HTTP " + responseCode));
				})
				.returnContent()
				.asJson(McAuthResponse.class);
	}

	public static McProfileResponse getUserProfile(McAuthResponse auth)
			throws IOException, InterruptedException, AuthenticationException {
		return getUserProfile(auth.getAuthorization());
	}

	public static McProfileResponse getUserProfile(String authorization)
			throws IOException, InterruptedException, AuthenticationException {
		return HttpRequest.get(MC_SERVICES_PROFILE)
				.header("Authorization", authorization)
				.execute()
				.expectResponseCodeOr(200, req -> {
					int responseCode = req.getResponseCode();
					HttpRequest.BufferedResponse content = req.returnContent();
					if (content.asBytes().length == 0) {
						if (responseCode == 404) {
							return new AuthenticationException("No Minecraft profile",
									SharedLocale.tr("login.minecraftNotOwnedError"));
						}
						return new AuthenticationException("Got empty response from Minecraft services",
								SharedLocale.tr("login.minecraft.error", responseCode));
					}

					McServicesError error = content.asJson(McServicesError.class);
					String errorCode = error.getErrorCode();

					if (responseCode == 404 || "NOT_FOUND".equals(errorCode)) {
						return new AuthenticationException("No Minecraft profile",
								SharedLocale.tr("login.minecraftNotOwnedError"));
					}

					String detail = error.getErrorMessage();
					if (detail == null || detail.isEmpty()) {
						detail = errorCode != null ? errorCode : ("HTTP " + responseCode);
					}

					return new AuthenticationException(detail,
							SharedLocale.tr("login.minecraft.error", detail));
				})
				.returnContent()
				.asJson(McProfileResponse.class);
	}
}
