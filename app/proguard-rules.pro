# Telepic ProGuard/R8 rules (release minification is enabled in app/build.gradle.kts).

# --- TDLib (io.github.tdlib-android:core, JNI package org.drinkless.tdlib) ---------------------
# libtdjni.so constructs TdApi.* response objects and invokes Client$RequestHandler callbacks by
# JNI name lookup: the whole binding must survive shrinking/renaming intact.
-keep class org.drinkless.tdlib.** { *; }
-dontwarn org.drinkless.tdlib.**

# --- WorkManager workers (instantiated reflectively by class name) ------------------------------
-keep class com.telepic.data.backup.work.BackupWorker {
    <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class com.telepic.data.backup.work.BackupDiscoveryWorker {
    <init>(android.content.Context, androidx.work.WorkerParameters);
}

# --- Readable crash reports from the obfuscated build --------------------------------------------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Room (generated code) and androidx Paging/DataStore/Media3/Coil ship their own consumer rules.
# osmdroid 6.1.20 does NOT — verified against the AAR, which contains no proguard.txt at all — and
# it is reflection- and resource-lookup-heavy, so it is the dependency most likely to need keeps
# here. A blanket keep is deliberate: osmdroid resolves tile sources, overlays and its configuration
# provider by class name, so obfuscating its package risks a release-only NoClassDefFoundError that
# no unit test can catch. The cost is a little APK size, against a `release-smoke` job that only
# compiles (it cannot exercise map rendering).
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**
#
# (The TDLib keep above is also redundant: the AAR ships its own proguard.txt containing exactly
# `-keep class org.drinkless.tdlib.** { *; }`. It is kept here as documentation of the contract.)
