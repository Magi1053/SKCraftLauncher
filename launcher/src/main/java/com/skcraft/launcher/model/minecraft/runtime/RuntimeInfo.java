package com.skcraft.launcher.model.minecraft.runtime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class RuntimeInfo {
    private DownloadInfo manifest;
    private Version version;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Version {
        private String name;
        private String released;
    }
}
