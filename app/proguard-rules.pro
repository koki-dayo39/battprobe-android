# Shizuku user service is instantiated reflectively in the shell process.
-keep class io.github.koki_dayo39.battprobe.shizuku.ShellService { *; }
-keep class io.github.koki_dayo39.battprobe.IShellService { *; }
-keep class io.github.koki_dayo39.battprobe.IShellService$Stub { *; }
-keep class rikka.shizuku.** { *; }
