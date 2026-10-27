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
# MapLibre's AAR ships consumer rules too, so the blanket keep below is belt-and-braces rather than a
# requirement — it is here because the cost of being wrong is a release-only NoClassDefFoundError or
# a JNI UnsatisfiedLinkError, and **nothing in CI compiles R8 at all**: there is no release job, so
# this file has never been exercised by automation and cannot be. Remove the blanket keep once a
# release build has been device-tested.
-keep class org.maplibre.android.** { *; }
-dontwarn org.maplibre.**
#
# (The TDLib keep above is also redundant: the AAR ships its own proguard.txt containing exactly
# `-keep class org.drinkless.tdlib.** { *; }`. It is kept here as documentation of the contract.)
