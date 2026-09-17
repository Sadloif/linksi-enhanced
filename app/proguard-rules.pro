# Linksi ProGuard Rules

# Keep Room entities
-keep class com.linksi.app.data.db.** { *; }

# Keep domain models (Parcelable)
-keep class com.linksi.app.domain.model.** { *; }

# Keep Hilt generated code
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# Keep Jsoup
-keep class org.jsoup.** { *; }

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Coil
-keep class coil.** { *; }

# youtubedl-android 0.17.3 (GPL-3.0, see app/build.gradle) and what it drags in.
#
# The library reflects over Jackson's mapper types and constructs an ObjectMapper in its static
# initialiser, so those classes have to survive shrinking even though this app reads
# `--dump-single-json` and never calls `getInfo`. Without these keeps a release build fails at the
# first `YoutubeDL.getInstance()` call, which is a runtime crash rather than a build error.
-keep class com.yausername.youtubedl_android.** { *; }
-keep class com.yausername.youtubedl_common.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keep class org.apache.commons.io.** { *; }
-dontwarn com.fasterxml.jackson.**
-dontwarn org.apache.commons.io.**
-dontwarn org.jspecify.annotations.NullMarked