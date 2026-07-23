package com.skcraft.launcher.launch;

import com.skcraft.launcher.Instance;
import com.skcraft.launcher.InstanceSettings;
import com.skcraft.launcher.model.modpack.LaunchModifier;
import com.skcraft.launcher.persistence.Persistence;
import com.sun.management.OperatingSystemMXBean;

import java.lang.management.ManagementFactory;
import java.util.function.BiFunction;

public final class MemoryRequirements {

	private static final int OS_RESERVE_MB = 2048;

	private MemoryRequirements() {
	}

	public static int getPhysicalMemoryCapMb() {
		try {
			java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
			if (bean instanceof OperatingSystemMXBean sunBean) {
				long totalMb = sunBean.getTotalMemorySize() / (1024L * 1024L);
				if (totalMb > 0) {
					return (int) Math.max(MemorySettings.DEFAULT_MIN_MEMORY, totalMb - OS_RESERVE_MB);
				}
			}
		} catch (Throwable ignored) {
		}
		return Integer.MAX_VALUE;
	}

	public static boolean verifyInstanceMemory(Instance instance,
			BiFunction<Integer, Integer, Runner.MemoryVerificationResult> memoryRequirementMismatch) {
		int systemCap = getPhysicalMemoryCapMb();
		MemorySettings.Resolved configured = MemorySettings.resolve(instance);
		int configuredMb = Math.max(configured.getMinMemory(), configured.getMaxMemory());

		LaunchModifier launchModifier = instance.getLaunchModifier();
		int requiredMb = launchModifier == null ? 0 : launchModifier.getMinMemory();
		if (requiredMb > systemCap) {
			LaunchSupervisor.MemoryVerifier.showInsufficientSystemMemory(instance, requiredMb, systemCap);
			return false;
		}
		if (configuredMb > systemCap) {
			LaunchSupervisor.MemoryVerifier.showInstanceMemoryExceedsSystem(instance, configuredMb, systemCap);
			return false;
		}
		if (launchModifier == null || memoryRequirementMismatch == null) {
			return true;
		}

		int requiredMin = launchModifier.getMinMemory();
		int currentMax = configured.getMaxMemory();
		if (requiredMin <= 0 || currentMax >= requiredMin) {
			return true;
		}

		Runner.MemoryVerificationResult result = memoryRequirementMismatch.apply(currentMax, requiredMin);
		if (result == Runner.MemoryVerificationResult.CANCEL) {
			return false;
		}
		if (result == Runner.MemoryVerificationResult.UPDATE_INSTANCE_SETTINGS) {
			MemorySettings.Resolved updated = MemorySettings.normalize(
					requiredMin, Math.max(currentMax, requiredMin));
			MemorySettings settings = getOrCreateMemorySettings(instance);
			settings.setMinMemory(updated.getMinMemory());
			settings.setMaxMemory(updated.getMaxMemory());
			Persistence.commitAndForget(instance);
		}
		return true;
	}

	private static MemorySettings getOrCreateMemorySettings(Instance instance) {
		InstanceSettings settings = instance.getSettings();
		if (settings.getMemorySettings() == null) {
			settings.setMemorySettings(new MemorySettings());
		}
		return settings.getMemorySettings();
	}
}
