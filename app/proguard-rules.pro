# Proguard rules for DroidTerminal
-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class com.droidterminal.runtime.NativePtyBridge { *; }
-keep class com.droidterminal.terminal.** { *; }
-keep class com.droidterminal.input.** { *; }
-keep class com.droidterminal.ui.** { *; }
