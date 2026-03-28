# Vosk speech recognition
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-dontwarn com.sun.jna.**

# Keep native method names
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep line numbers for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
