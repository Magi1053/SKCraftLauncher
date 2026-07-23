package com.skcraft.launcher.launch.runtime;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.skcraft.launcher.util.Environment;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class LinuxRuntimeFinder implements PlatformRuntimeFinder {
	@Override
	public List<JavaRuntime> getSystemRuntimes(Environment env) {
		ArrayList<JavaRuntime> entries = Lists.newArrayList();

		entries.addAll(MinecraftJavaFinder.scanLauncherDirectories(env,
				ImmutableSet.of(new File(System.getenv("HOME"), ".minecraft"))));

		for (File candidate : getCandidateJavaLocations()) {
			JavaRuntime runtime = JavaRuntimeFinder.getRuntimeFromPath(candidate);
			if (runtime != null) {
				entries.add(runtime);
			}
		}

		return entries;
	}

	private static List<File> getCandidateJavaLocations() {
		ArrayList<File> entries = Lists.newArrayList();

		String javaHome = System.getenv("JAVA_HOME");
		if (javaHome != null) {
			entries.add(new File(javaHome));
		}

		File[] runtimesList = new File("/usr/lib/jvm").listFiles();
		if (runtimesList != null) {
			Arrays.stream(runtimesList).map(file -> {
				try {
					return file.getCanonicalFile();
				} catch (IOException exception) {
					return file;
				}
			}).distinct().forEach(entries::add);
		}

		return entries;
	}
}
