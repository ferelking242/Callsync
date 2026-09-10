-keep class io.flutter.** { *; }
-keep class io.flutter.embedding.** { *; }
-keep class io.flutter.plugin.** { *; }

# just_audio
-keep class com.ryanheise.just_audio.** { *; }

# Keep Dart entry points
-keep class **.GeneratedPluginRegistrant { *; }

# Flutter Play Store deferred components — classes absent sans Play Core dep
-dontwarn com.google.android.play.core.**
-keep class com.google.android.play.core.** { *; }
