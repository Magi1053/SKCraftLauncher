package com.skcraft.launcher.model.minecraft.runtime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import lombok.Data;

import java.util.Map;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = RuntimeManifestEntry.File.class, name = "file"),
        @JsonSubTypes.Type(value = RuntimeManifestEntry.Directory.class, name = "directory"),
        @JsonSubTypes.Type(value = RuntimeManifestEntry.Link.class, name = "link"),
})
public interface RuntimeManifestEntry {
    @Data
    @JsonTypeName("file")
    @JsonIgnoreProperties(ignoreUnknown = true)
    class File implements RuntimeManifestEntry {
        private Map<Format, DownloadInfo> downloads;
        private boolean executable;
    }

    @JsonTypeName("directory")
    @JsonIgnoreProperties(ignoreUnknown = true)
    class Directory implements RuntimeManifestEntry {
    }

    @Data
    @JsonTypeName("link")
    @JsonIgnoreProperties(ignoreUnknown = true)
    class Link implements RuntimeManifestEntry {
        private String target;
    }
}
