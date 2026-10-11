# Shizuku user service is instantiated reflectively in the shell process.
-keep class io.github.koki_dayo39.batterystatus.shizuku.ShellService { *; }
-keep class io.github.koki_dayo39.batterystatus.IShellService { *; }
-keep class io.github.koki_dayo39.batterystatus.IShellService$Stub { *; }
-keep class rikka.shizuku.** { *; }
