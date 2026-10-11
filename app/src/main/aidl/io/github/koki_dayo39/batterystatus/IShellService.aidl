package io.github.koki_dayo39.batterystatus;

interface IShellService {
    // Reserved transaction code used by the Shizuku server to tear the service down.
    void destroy() = 16777114;

    void exit() = 1;

    /**
     * Runs `sh -c <command>` as the Shizuku (shell/root) user and returns stdout+stderr.
     * If the command runs longer than timeoutMs it is killed and the partial output is
     * returned with a trailing "###TIMEOUT" line.
     */
    String exec(String command, long timeoutMs) = 2;

    int getUid() = 3;
}
