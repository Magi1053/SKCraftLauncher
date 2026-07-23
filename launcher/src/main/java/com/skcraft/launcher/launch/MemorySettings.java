package com.skcraft.launcher.launch;

import com.skcraft.launcher.Instance;
import com.skcraft.launcher.model.modpack.Feature;
import com.skcraft.launcher.model.modpack.LaunchModifier;
import lombok.Data;
import lombok.Value;

import java.util.List;
import java.util.Locale;

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

		return normalize(minMemory, maxMemory);
	}

	public static String formatMemoryGb(int memoryMb) {
		return String.format(Locale.US, memoryMb % 1024 == 0 ? "%.0f" : "%.1f", memoryMb / 1024.0);
	}

	public static Resolved normalize(int minMemory, int maxMemory) {
		if (minMemory <= 0) {
			minMemory = DEFAULT_MIN_MEMORY;
		}

		if (maxMemory <= 0) {
			maxMemory = DEFAULT_MAX_MEMORY;
		}

		if (minMemory > maxMemory) {
			maxMemory = minMemory;
		}

		return new Resolved(minMemory, maxMemory);
	}

	public static LaunchModifier computeEffectiveLaunchModifier(LaunchModifier base, List<Feature> features) {
		LaunchModifier modifier = new LaunchModifier(base);
		Resolved memory = normalize(modifier);

		if (features != null) {
			for (Feature feature : features) {
				if (feature != null && feature.isSelected() && feature.getMinMemoryDelta() > 0) {
					memory = normalize(memory.getMinMemory() + feature.getMinMemoryDelta(), memory.getMaxMemory());
				}
			}
		}

		modifier.setMinMemory(memory.getMinMemory());
		modifier.setMaxMemory(memory.getMaxMemory());
		return modifier;
	}

	public static void syncDefaultsFromManifest(
			Instance instance, LaunchModifier previousEffective, LaunchModifier newEffective) {
		boolean hadSettings = instance.getSettings().getMemorySettings() != null;
		MemorySettings settings = getOrCreateSettings(instance);
		Resolved nextDefaults = normalize(newEffective);
		if (!hadSettings) {
			settings.setMinMemory(nextDefaults.getMinMemory());
			settings.setMaxMemory(nextDefaults.getMaxMemory());
			return;
		}

		Resolved current = normalize(settings.getMinMemory(), settings.getMaxMemory());
		Resolved previousDefaults = previousEffective == null
				? normalize(DEFAULT_MIN_MEMORY, DEFAULT_MAX_MEMORY)
				: normalize(previousEffective);
		int nextMinMemory = current.getMinMemory();
		int nextMaxMemory = current.getMaxMemory();

		if (nextMinMemory == previousDefaults.getMinMemory()) {
			nextMinMemory = nextDefaults.getMinMemory();
		}
		if (nextMaxMemory == previousDefaults.getMaxMemory()
				&& nextDefaults.getMaxMemory() > previousDefaults.getMaxMemory()) {
			nextMaxMemory = nextDefaults.getMaxMemory();
		}
		Resolved updated = normalize(nextMinMemory, nextMaxMemory);
		settings.setMinMemory(updated.getMinMemory());
		settings.setMaxMemory(updated.getMaxMemory());
	}

	private static Resolved normalize(LaunchModifier modifier) {
		if (modifier == null) {
			return normalize(DEFAULT_MIN_MEMORY, DEFAULT_MAX_MEMORY);
		}
		return normalize(modifier.getMinMemory(), modifier.getMaxMemory());
	}

	private static MemorySettings getOrCreateSettings(Instance instance) {
		MemorySettings settings = instance.getSettings().getMemorySettings();
		if (settings == null) {
			settings = new MemorySettings();
			instance.getSettings().setMemorySettings(settings);
		}
		return settings;
	}
}
