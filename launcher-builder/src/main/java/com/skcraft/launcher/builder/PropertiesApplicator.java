/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.builder;

import com.skcraft.launcher.model.modpack.*;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class PropertiesApplicator {

    private final Manifest manifest;
    private final Set<Feature> used = new HashSet<Feature>();
    private final List<FeaturePattern> features = new ArrayList<FeaturePattern>();
    @Getter @Setter
    private FnPatternList userFiles;

    public PropertiesApplicator(Manifest manifest) {
        this.manifest = manifest;
    }

    public void apply(ManifestEntry entry) {
        if (entry instanceof FileInstall) {
            apply((FileInstall) entry);
        }
    }

    private void apply(FileInstall entry) {
        String path = entry.getTargetPath();
        entry.setWhen(fromFeature(path));
        entry.setUserFile(isUserFile(path));
    }

    public boolean isUserFile(String path) {
        if (userFiles != null) {
            return userFiles.matches(path);
        } else {
            return false;
        }
    }

    public Condition fromFeature(String path) {
        List<Feature> found = new ArrayList<Feature>();
        List<Feature> excluded = new ArrayList<Feature>();
        for (FeaturePattern pattern : features) {
            boolean includesPath = pattern.matchesInclude(path);
            boolean excludesPath = pattern.excludes(path);

            if (includesPath || excludesPath) {
                used.add(pattern.getFeature());
            }

            if (includesPath && !excludesPath) {
                found.add(pattern.getFeature());
            }

            if (excludesPath) {
                excluded.add(pattern.getFeature());
            }
        }

        if (!found.isEmpty() && !excluded.isEmpty()) {
            return new RequireAnyAndNone(found, excluded);
        } else if (!found.isEmpty()) {
            return new RequireAny(found);
        } else if (!excluded.isEmpty()) {
            return new RequireNone(excluded);
        } else {
            return null;
        }
    }

    public void register(FeaturePattern component) {
        features.add(component);
    }

    public List<Feature> getFeaturesInUse() {
        List<Feature> ordered = new ArrayList<Feature>();
        for (FeaturePattern pattern : features) {
            Feature feature = pattern.getFeature();
            if (used.contains(feature)) {
                ordered.add(feature);
            }
        }
        return ordered;
    }

}
