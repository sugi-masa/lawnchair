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
 * Definition for the Folder Home state (non-quickstep variant, mirrors the quickstep one).
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
        return NONE;
    }

    @Override
    public PageAlphaProvider getWorkspacePageAlphaProvider(Launcher launcher) {
        // Mirror of the quickstep variant: keep the workspace pages' contents
        // from showing through the translucent folder home layer.
        return new PageAlphaProvider(DECELERATE_2) {
            @Override
            public float getPageAlpha(int pageIndex) {
                return 0;
            }
        };
    }
}
