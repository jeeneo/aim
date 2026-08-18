-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

-keep class org.codeberg.aimapp.utils.ImageProvider {
    public protected *;
}

-keep class org.codeberg.aimapp.utils.mounts.ImageStore {
    public *;
}

-keepclassmembers enum org.codeberg.aimapp.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-keepclassmembers class org.codeberg.aimapp.utils.mounts.MountedImage { *; }
-keepclassmembers class org.codeberg.aimapp.utils.mounts.PartitionedImageResult { *; }
-keepclassmembers class org.codeberg.aimapp.utils.mounts.EnvironmentStatus { *; }
-keepclassmembers class org.codeberg.aimapp.ImportedImage { *; }
-keepclassmembers class org.codeberg.aimapp.ImageInfo { *; }
-keepclassmembers class org.codeberg.aimapp.PartitionState { *; }
-keepclassmembers class org.codeberg.aimapp.utils.shell.ShellResult { *; }
-keepclassmembers class org.codeberg.aimapp.utils.mounts.PartitionEntry { *; }
-keepclassmembers class org.codeberg.aimapp.utils.mounts.PartitionTableInfo { *; }
-keepclassmembers class org.codeberg.aimapp.utils.paths.ResolvedImage { *; }
-keepclassmembers class org.codeberg.aimapp.utils.disk.PartitionedImageException { *; }

-keep class org.codeberg.aimapp.utils.shell.ShellCmd { *; }
-keep class org.codeberg.aimapp.utils.shell.ShellArg { *; }
-keep class org.codeberg.aimapp.utils.shell.RootShell { *; }

-keep class android.util.Log { *; }
-keep class java.util.logging.** { *; }
-keepclassmembers class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}
