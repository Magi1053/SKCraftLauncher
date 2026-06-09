package com.skcraft.launcher.update.runtime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class RuntimeData {
    private String component;
    private String platform;
    private String version;
    private String manifestSha1;
    private String javaHomePath;

    @JsonProperty("64Bit")
    private boolean is64Bit;
}
