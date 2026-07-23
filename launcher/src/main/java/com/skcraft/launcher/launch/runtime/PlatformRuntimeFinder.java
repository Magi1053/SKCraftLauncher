package com.skcraft.launcher.launch.runtime;

import com.skcraft.launcher.util.Environment;

import java.util.List;

public interface PlatformRuntimeFinder {
	/**
	 * Discover Java runtimes available on this platform (vendor installs,
	 * Mojang launcher bundles, registry / java_home / etc.).
	 *
	 * @param env current environment
	 * @return system Java runtimes (may contain duplicates by path)
	 */
	List<JavaRuntime> getSystemRuntimes(Environment env);
}
