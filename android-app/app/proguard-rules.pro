# Keep Sentry SDK internals
-keep class io.sentry.** { *; }
-keepnames class io.sentry.** { *; }

# Hilt
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.lifecycle.HiltViewModel { *; }
