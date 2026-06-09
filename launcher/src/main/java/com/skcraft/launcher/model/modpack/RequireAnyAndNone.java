/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.model.modpack;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class RequireAnyAndNone implements Condition {

    private List<Feature> requireAny = new ArrayList<Feature>();
    private List<Feature> requireNone = new ArrayList<Feature>();

    public RequireAnyAndNone() {
    }

    public RequireAnyAndNone(List<Feature> requireAny, List<Feature> requireNone) {
        this.requireAny = requireAny;
        this.requireNone = requireNone;
    }

    @Override
    public boolean matches() {
        return new RequireAny(requireAny).matches()
                && new RequireNone(requireNone).matches();
    }

}
