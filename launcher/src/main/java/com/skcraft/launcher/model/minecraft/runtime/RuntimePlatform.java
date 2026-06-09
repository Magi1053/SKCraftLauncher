package com.skcraft.launcher.model.minecraft.runtime;

import com.skcraft.launcher.util.Environment;
import com.skcraft.launcher.util.Platform;
import lombok.Getter;

public enum RuntimePlatform {
    LINUX("linux", true),
    LINUX_386("linux-i386", false),
    MAC_OS("mac-os", true),
    MAC_OS_ARM64("mac-os-arm64", true),
    WINDOWS_ARM64("windows-arm64", true),
    WINDOWS_X64("windows-x64", true),
    WINDOWS_X86("windows-x86", false);

    @Getter
    private final String id;
    @Getter
    private final boolean is64Bit;

    RuntimePlatform(String id, boolean is64Bit) {
        this.id = id;
        this.is64Bit = is64Bit;
    }

    public static RuntimePlatform from(Environment environment) {
        Platform platform = environment.getPlatform();
        String arch = environment.getArch() != null ? environment.getArch().toLowerCase() : "";

        switch (platform) {
            case WINDOWS:
                if (isArm64(arch)) return WINDOWS_ARM64;
                if (is32BitX86(arch)) return WINDOWS_X86;
                return WINDOWS_X64;
            case MAC_OS_X:
                if (isArm64(arch)) return MAC_OS_ARM64;
                return MAC_OS;
            case LINUX:
                if (is32BitX86(arch)) return LINUX_386;
                return LINUX;
            default:
                return null;
        }
    }

    private static boolean isArm64(String arch) {
        return arch.equals("aarch64") || arch.equals("arm64");
    }

    private static boolean is32BitX86(String arch) {
        return arch.equals("x86") || arch.equals("i386") || arch.equals("i686");
    }
}
