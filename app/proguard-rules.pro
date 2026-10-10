# Shizuku user service is instantiated reflectively in the shell process.
-keep class com.kokidayo.batterystatus.shizuku.ShellService { *; }
-keep class com.kokidayo.batterystatus.IShellService { *; }
-keep class com.kokidayo.batterystatus.IShellService$Stub { *; }
-keep class rikka.shizuku.** { *; }
