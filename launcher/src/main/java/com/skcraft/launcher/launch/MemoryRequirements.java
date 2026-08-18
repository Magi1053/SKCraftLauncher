package com.skcraft.launcher.launch;

import com.skcraft.launcher.Instance;
import com.skcraft.launcher.InstanceSettings;
import com.skcraft.launcher.model.modpack.LaunchModifier;
import com.skcraft.launcher.persistence.Persistence;
import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.management.OperatingSystemMXBean;

import java.lang.management.ManagementFactory;
import java.util.function.BiFunction;

public final class MemoryRequirements {

	private static final int SYSTEM_HEADROOM_MB = 1024;

	private MemoryRequirements() {
	}

	public static int getPhysicalMemoryCapMb() {
		long totalBytes = 0;
		if (Platform.isWindows()) {
			try {
				WinBase.MEMORYSTATUSEX status = new WinBase.MEMORYSTATUSEX();
				if (Kernel32.INSTANCE.GlobalMemoryStatusEx(status)) {
					totalBytes = status.ullTotalPhys.longValue();
				}
			} catch (Throwable ignored) {
			}
		}
		if (totalBytes <= 0) {
			try {
				java.lang.management.OperatingSystemMXBean bean =
						ManagementFactory.getOperatingSystemMXBean();
				if (bean instanceof OperatingSystemMXBean sunBean) {
					totalBytes = sunBean.getTotalMemorySize();
				}
			} catch (Throwable ignored) {
			}
		}

		if (totalBytes <= 0) {
			return -1;
		}
		long totalMb = totalBytes / (1024L * 1024L);
		return (int) Math.min(Integer.MAX_VALUE, Math.max(0, totalMb - SYSTEM_HEADROOM_MB));
	}

	public static boolean verifyInstanceMemory(Instance instance,
			BiFunction<Integer, Integer, Runner.MemoryVerificationResult> memoryRequirementMismatch) {
		int systemCap = getPhysicalMemoryCapMb();
		MemorySettings.Resolved configured = MemorySettings.resolve(instance);
		LaunchModifier launchModifier = instance.getLaunchModifier();
		int requiredMb = launchModifier == null ? 0 : launchModifier.getMinMemory();
		int requiredHeapMb = Math.max(requiredMb, configured.getMinMemory());
		if (systemCap >= 0 && requiredHeapMb > systemCap
				&& !LaunchSupervisor.MemoryVerifier.confirmInsufficientSystemMemory(
						instance, requiredHeapMb, systemCap)) {
			return false;
		}
		if (systemCap >= 0 && requiredHeapMb <= systemCap
				&& configured.getMaxMemory() > systemCap
				&& !LaunchSupervisor.MemoryVerifier.confirmInstanceMemoryExceedsSystem(
						instance, configured.getMaxMemory(), systemCap)) {
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
