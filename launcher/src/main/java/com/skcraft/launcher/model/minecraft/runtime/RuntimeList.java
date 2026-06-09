package com.skcraft.launcher.model.minecraft.runtime;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.skcraft.launcher.model.minecraft.JavaVersion;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class RuntimeList {
    @JsonValue
    private final Map<String, Map<String, List<RuntimeInfo>>> runtimesByPlatform;

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public RuntimeList(Map<String, Map<String, List<RuntimeInfo>>> runtimesByPlatform) {
        this.runtimesByPlatform = runtimesByPlatform;
    }

    public RuntimeInfo getRuntime(RuntimePlatform platform, JavaVersion target) {
        if (platform == null || target == null || target.getComponent() == null) {
            return null;
        }

        Map<String, List<RuntimeInfo>> platformRuntimes = runtimesByPlatform.get(platform.getId());
        if (platformRuntimes == null) {
            return null;
        }

        List<RuntimeInfo> versions = platformRuntimes.get(target.getComponent());
        if (versions == null || versions.isEmpty()) {
            return null;
        }

        return versions.get(0);
    }
}
