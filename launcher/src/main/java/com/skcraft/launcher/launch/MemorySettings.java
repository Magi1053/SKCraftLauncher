package com.skcraft.launcher.launch;

import com.skcraft.launcher.Instance;
import com.skcraft.launcher.model.modpack.LaunchModifier;
import lombok.Data;
import lombok.Value;

/**
 * Settings for launched process memory allocation.
 */
@Data
public class MemorySettings {

	public static final int DEFAULT_MIN_MEMORY = 1024;
	public static final int DEFAULT_MAX_MEMORY = 4096;

	/**
	 * Minimum memory in megabytes.
	 */
	private int minMemory = DEFAULT_MIN_MEMORY;

	/**
	 * Maximum memory in megabytes.
	 */
	private int maxMemory = DEFAULT_MAX_MEMORY;

	@Value
	public static class Resolved {
		int minMemory;
		int maxMemory;
	}

	/**
	 * Resolve effective memory for launch: instance settings, then manifest launch
	 * block, then defaults.
	 */
	public static Resolved resolve(Instance instance) {
		int minMemory = DEFAULT_MIN_MEMORY;
		int maxMemory = DEFAULT_MAX_MEMORY;

		LaunchModifier launch = instance.getLaunchModifier();
		if (launch != null) {
			if (launch.getMinMemory() > 0) {
				minMemory = launch.getMinMemory();
			}
			if (launch.getMaxMemory() > 0) {
				maxMemory = launch.getMaxMemory();
			}
		}

		MemorySettings settings = instance.getSettings().getMemorySettings();
		if (settings != null) {
			if (settings.getMinMemory() > 0) {
				minMemory = settings.getMinMemory();
			}
			if (settings.getMaxMemory() > 0) {
				maxMemory = settings.getMaxMemory();
			}
		}

		if (minMemory <= 0) {
			minMemory = DEFAULT_MIN_MEMORY;
		}

		if (maxMemory <= 0) {
			maxMemory = DEFAULT_MIN_MEMORY;
		}

		if (minMemory > maxMemory) {
			maxMemory = minMemory;
		}

		return new Resolved(minMemory, maxMemory);
	}

	/**
	 * Copy manifest launch memory into instance settings when values are set.
	 */
	public static void applyFromLaunchModifier(Instance instance, LaunchModifier modifier) {
		if (modifier == null) {
			return;
		}

		MemorySettings settings = instance.getSettings().getMemorySettings();
		if (settings == null) {
			settings = new MemorySettings();
			instance.getSettings().setMemorySettings(settings);
		}

		if (modifier.getMinMemory() > 0) {
			settings.setMinMemory(modifier.getMinMemory());
		}
		if (modifier.getMaxMemory() > 0) {
			settings.setMaxMemory(modifier.getMaxMemory());
		}
		if (settings.getMinMemory() > settings.getMaxMemory()) {
			settings.setMaxMemory(settings.getMinMemory());
		}
	}
}
