/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.model.modpack;

import lombok.Data;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Data
public class RequireNone implements Condition {

    private List<Feature> features = new ArrayList<Feature>();

    public RequireNone() {
    }

    public RequireNone(List<Feature> features) {
        this.features = features;
    }

    public RequireNone(Feature... feature) {
        features.addAll(Arrays.asList(feature));
    }

    @Override
    public boolean matches() {
        if (features == null) {
            return true;
        }

        for (Feature feature : features) {
            if (feature.isSelected()) {
                return false;
            }
        }

        return true;
    }

}
