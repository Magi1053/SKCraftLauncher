/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.builder;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.skcraft.launcher.model.modpack.Feature;
import lombok.Data;

@Data
public class FeaturePattern {

    @JsonProperty("properties")
    private Feature feature;
    @JsonProperty("files")
    private FnPatternList filePatterns = new FnPatternList();

    public boolean matchesInclude(String path) {
        return filePatterns != null && filePatterns.matchesInclude(path);
    }

    public boolean excludes(String path) {
        return filePatterns != null && filePatterns.matchesExclude(path);
    }
}
