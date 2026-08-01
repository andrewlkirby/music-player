# ── MusicPlayer ProGuard rules ───────────────────────────────────────────────

# Keep Hilt-generated components
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager { *; }

# Keep Room entities and DAOs
-keep class com.musicplayer.data.local.entities.** { *; }
-keep class com.musicplayer.data.local.dao.** { *; }

# Keep domain models (used in serialisation, MediaMetadata extras, etc.)
-keep class com.musicplayer.domain.model.** { *; }

# JAudioTagger — uses reflection heavily
-keep class org.jaudiotagger.** { *; }
-dontwarn org.jaudiotagger.**

# Keep WorkManager worker classes
-keep class com.musicplayer.worker.** { *; }

# Media3 / ExoPlayer
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Coil
-keep class coil.** { *; }
-dontwarn coil.**

# Kotlin coroutines
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# Kotlin serialisation (if used for queue JSON in future)
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# Suppress warnings for missing optional dependencies
-dontwarn org.slf4j.**
-dontwarn javax.annotation.**
