package org.bifrost;

import org.libsdl.app.SDLActivity;

/** SDL activity for renderer builds: loads SDL3, shaderc and the recompiled title (libmain.so). */
public class BifrostActivity extends SDLActivity {
    @Override
    protected String[] getLibraries() {
        return new String[] {"SDL3", "shaderc_shared", "main"};
    }
}
