package com.skcraft.launcher.model.minecraft.runtime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class RuntimeManifest {
    private Map<String, RuntimeManifestEntry> files;
}
