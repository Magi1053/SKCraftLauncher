package com.skcraft.launcher.update.runtime;

import com.skcraft.launcher.launch.runtime.JavaRuntime;
import com.skcraft.launcher.util.SharedLocale;
import lombok.Data;

@Data
public class ManagedRuntimeOption {
	private final String component;
	private final int majorVersion;
	private final String version;
	private final boolean is64Bit;
	private final boolean installed;
	private final JavaRuntime runtime;

	public String getDisplayLabel() {
		String versionLabel = version != null && !version.isEmpty()
				? version
				: majorVersion > 0 ? formatJavaMajorVersion(majorVersion) : component;
		return formatLabel(versionLabel, is64Bit, "Mojang");
	}

	public static String formatLabel(String version, boolean is64Bit, String detail) {
		String bitness = is64Bit ? "64-bit" : "32-bit";
		return SharedLocale.tr("instance.options.javaRuntime", version, bitness, detail);
	}

	@Override
	public String toString() {
		return getDisplayLabel();
	}

	public static String formatJavaMajorVersion(int majorVersion) {
		return majorVersion == 8 ? "1.8" : String.valueOf(majorVersion);
	}
}
