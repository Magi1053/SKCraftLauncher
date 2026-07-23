package com.skcraft.launcher.launch.runtime;

import com.google.common.collect.Lists;
import com.skcraft.launcher.util.Environment;
import com.skcraft.launcher.util.WinRegistry;
import com.sun.jna.platform.win32.WinReg;
import lombok.extern.java.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;

@Log
public class WindowsRuntimeFinder implements PlatformRuntimeFinder {
	private static final WinReg.HKEY[] HIVES = {
			WinReg.HKEY_LOCAL_MACHINE,
			WinReg.HKEY_CURRENT_USER,
	};

	/** Registry locations that store installed Java homes. */
	private static final RegistrySpec[] REGISTRY_SPECS = {
			// Oracle / JavaSoft (also covers Corretto when its JavaSoft MSI feature is
			// enabled)
			new RegistrySpec("SOFTWARE\\JavaSoft\\Java Runtime Environment", "JavaHome", ""),
			new RegistrySpec("SOFTWARE\\JavaSoft\\Java Development Kit", "JavaHome", ""),
			new RegistrySpec("SOFTWARE\\JavaSoft\\JRE", "JavaHome", ""),
			new RegistrySpec("SOFTWARE\\JavaSoft\\JDK", "JavaHome", ""),

			// Eclipse Adoptium (Temurin) and legacy AdoptOpenJDK / Eclipse Foundation keys
			new RegistrySpec("SOFTWARE\\Eclipse Adoptium\\JRE", "Path", "\\hotspot\\MSI"),
			new RegistrySpec("SOFTWARE\\Eclipse Adoptium\\JDK", "Path", "\\hotspot\\MSI"),
			new RegistrySpec("SOFTWARE\\Eclipse Foundation\\JDK", "Path", "\\hotspot\\MSI"),
			new RegistrySpec("SOFTWARE\\AdoptOpenJDK\\JRE", "Path", "\\hotspot\\MSI"),
			new RegistrySpec("SOFTWARE\\AdoptOpenJDK\\JDK", "Path", "\\hotspot\\MSI"),

			// IBM Semeru
			new RegistrySpec("SOFTWARE\\Semeru\\JRE", "Path", "\\openj9\\MSI"),
			new RegistrySpec("SOFTWARE\\Semeru\\JDK", "Path", "\\openj9\\MSI"),

			// Azul Zulu
			new RegistrySpec("SOFTWARE\\Azul Systems\\Zulu", "InstallationPath", ""),

			// Microsoft Build of OpenJDK
			new RegistrySpec("SOFTWARE\\Microsoft\\JDK", "Path", "\\hotspot\\MSI"),
	};

	/** Default 64-bit install roots under %ProgramFiles%. */
	private static final String[] VENDOR_DIR_ROOTS = {
			"Eclipse Adoptium",
			"Eclipse Foundation",
			"Zulu",
			"Amazon Corretto",
			"Semeru",
			"Microsoft",
	};

	@Override
	public List<JavaRuntime> getSystemRuntimes(Environment env) {
		ArrayList<JavaRuntime> entries = Lists.newArrayList();

		entries.addAll(MinecraftJavaFinder.scanLauncherDirectories(
				env, getMinecraftLauncherDirectories(env)));

		for (File candidate : getCandidateJavaLocations()) {
			JavaRuntime runtime = JavaRuntimeFinder.getRuntimeFromPath(candidate);
			if (runtime != null) {
				entries.add(runtime);
			}
		}

		for (RegistrySpec spec : REGISTRY_SPECS) {
			for (WinReg.HKEY hive : HIVES) {
				getEntriesFromRegistry(entries, hive, spec.basePath, spec.valueName, spec.subkeySuffix);
			}
		}

		return entries;
	}

	private static List<File> getMinecraftLauncherDirectories(Environment env) {
		List<File> launcherDirs = new ArrayList<>();

		WinRegistry.readStringOptional(WinReg.HKEY_CURRENT_USER,
				"SOFTWARE\\Mojang\\InstalledProducts\\Minecraft Launcher", "InstallLocation")
				.map(File::new)
				.ifPresent(launcherDirs::add);

		String programFiles = Objects.equals(env.getArchBits(), "64")
				? System.getenv("ProgramFiles(x86)")
				: System.getenv("ProgramFiles");
		if (programFiles != null) {
			launcherDirs.add(new File(programFiles, "Minecraft"));
			launcherDirs.add(new File(programFiles, "Minecraft Launcher"));
		}

		String localAppData = System.getenv("LOCALAPPDATA");
		if (localAppData != null) {
			launcherDirs.add(new File(localAppData,
					"Packages\\Microsoft.4297127D64EC6_8wekyb3d8bbwe\\LocalCache\\Local"));
		}

		return launcherDirs;
	}

	private static List<File> getCandidateJavaLocations() {
		List<File> candidates = new ArrayList<>();
		String programFiles = System.getenv("ProgramFiles");
		if (programFiles == null) {
			return candidates;
		}

		for (String root : VENDOR_DIR_ROOTS) {
			File[] children = new File(programFiles, root).listFiles(File::isDirectory);
			if (children != null) {
				Collections.addAll(candidates, children);
			}
		}

		return candidates;
	}

	private static void getEntriesFromRegistry(Collection<JavaRuntime> entries, WinReg.HKEY hive,
			String basePath, String valueName, String subkeySuffix) {
		if (!WinRegistry.keyExists(hive, basePath)) {
			// Key isn't present (no such Java installed) - nothing to read, don't log.
			return;
		}

		try {
			List<String> subKeys = WinRegistry.readStringSubKeys(hive, basePath);
			for (String subKey : subKeys) {
				JavaRuntime entry = getEntryFromRegistry(hive, basePath, subKey, valueName, subkeySuffix);
				if (entry != null) {
					entries.add(entry);
				}
			}
		} catch (Throwable err) {
			log.log(Level.INFO, "Failed to read Java locations from registry in " + basePath, err);
		}
	}

	private static JavaRuntime getEntryFromRegistry(WinReg.HKEY hive, String basePath, String version,
			String valueName, String subkeySuffix) {
		String regPath = basePath + "\\" + version + subkeySuffix;
		try {
			String path = WinRegistry.readString(hive, regPath, valueName);
			if (path == null || path.isEmpty()) {
				return null;
			}
			return JavaRuntimeFinder.getRuntimeFromPath(new File(path));
		} catch (Throwable err) {
			// Subkey may not have the expected value (e.g. CurrentVersion sibling keys).
			return null;
		}
	}

	private static final class RegistrySpec {
		final String basePath;
		final String valueName;
		final String subkeySuffix;

		RegistrySpec(String basePath, String valueName, String subkeySuffix) {
			this.basePath = basePath;
			this.valueName = valueName;
			this.subkeySuffix = subkeySuffix;
		}
	}
}
