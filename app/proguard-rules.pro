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

# Room (generated code), androidx Paging/DataStore/Media3/Coil and osmdroid ship their own consumer
# rules; add project-specific keeps here only if a minified release smoke test proves otherwise.
