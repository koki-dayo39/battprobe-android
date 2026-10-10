package com.kokidayo.batterystatus;

interface IShellService {
    // Reserved transaction code used by the Shizuku server to tear the service down.
    void destroy() = 16777114;

    void exit() = 1;

    /** Runs `sh -c <command>` as the Shizuku (shell/root) user and returns stdout+stderr. */
    String exec(String command) = 2;

    int getUid() = 3;
}
