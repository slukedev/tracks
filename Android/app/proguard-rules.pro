# UniFFI's generated bindings call into the engine through JNA, which reflects on these.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class uniffi.tracks_engine.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**
