package com.skcraft.launcher.auth.microsoft.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class McServicesError {
	private String error;
	private String errorType;
	private String errorMessage;

	@JsonIgnore
	public String getErrorCode() {
		if (error != null && !error.isEmpty()) {
			return error;
		}
		if (errorType != null && !errorType.isEmpty()) {
			return errorType;
		}
		return null;
	}
}
