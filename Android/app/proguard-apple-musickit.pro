# MusicKit for Android. The AARs ship JavaCPP's build tools and a data binding mapper that are never used at run time.
-dontwarn org.bytedeco.javacpp.tools.**
-dontwarn org.slf4j.**
-dontwarn org.apache.maven.**
-dontwarn androidx.databinding.**
# Their classes are obfuscated already and reached from native code by name.
-keep class com.apple.android.** { *; }
