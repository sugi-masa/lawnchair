/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.launcher3.uioverrides.states;

import static com.android.app.animation.Interpolators.DECELERATE_2;
import static com.android.launcher3.logging.StatsLogManager.LAUNCHER_STATE_HOME;

import com.android.launcher3.Launcher;
import com.android.launcher3.LauncherState;
import com.android.launcher3.views.ActivityContext;

/**
 * Definition for the Folder Home state.
 *
 * <p>Folder Home is a full-screen layer shown above the workspace (and below All Apps). It is
 * the top layer at launcher startup and the surface the home intent returns to. The workspace
 * underneath keeps its normal scale; only its elements are hidden while this state is active.
 */
public class FolderHomeState extends LauncherState {

    private static final int STATE_FLAGS =
            FLAG_WORKSPACE_INACCESSIBLE | FLAG_CLOSE_POPUPS | FLAG_HOTSEAT_INACCESSIBLE;

    private static final int TRANSITION_DURATION_MS = 300;

    public FolderHomeState(int id) {
        super(id, LAUNCHER_STATE_HOME, STATE_FLAGS);
    }

    @Override
    public int getTransitionDuration(ActivityContext context, boolean isToState) {
        return TRANSITION_DURATION_MS;
    }

    @Override
    public int getVisibleElements(Launcher launcher) {
        // The folder home layer covers the whole screen, so no workspace element
        // (hotseat icons, page indicator, floating search bar) should be shown.
        return NONE;
    }

    @Override
    public PageAlphaProvider getWorkspacePageAlphaProvider(Launcher launcher) {
        // The folder home layer is translucent, so the contents of the workspace
        // pages underneath (icons, widgets, first-run text) must not show through
        // it. Fading every page out here only touches the pages' contents; the
        // wallpaper is drawn behind the launcher window and stays visible.
        return new PageAlphaProvider(DECELERATE_2) {
            @Override
            public float getPageAlpha(int pageIndex) {
                return 0;
            }
        };
    }
}
