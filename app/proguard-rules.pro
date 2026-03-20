-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

-keep class org.codeberg.aimapp.ImageProvider {
    public protected *;
}

-keep class org.codeberg.aimapp.ImageStore {
    public *;
}

-keepclassmembers enum org.codeberg.aimapp.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-keepclassmembers class org.codeberg.aimapp.MountedImage { *; }
-keepclassmembers class org.codeberg.aimapp.PartitionedImageResult { *; }
-keepclassmembers class org.codeberg.aimapp.EnvironmentStatus { *; }
-keepclassmembers class org.codeberg.aimapp.ImportedImage { *; }
-keepclassmembers class org.codeberg.aimapp.ImageInfo { *; }
-keepclassmembers class org.codeberg.aimapp.PartitionState { *; }
-keepclassmembers class org.codeberg.aimapp.utils.ShellResult { *; }
-keepclassmembers class org.codeberg.aimapp.utils.PartitionEntry { *; }
-keepclassmembers class org.codeberg.aimapp.utils.PartitionTableInfo { *; }
-keepclassmembers class org.codeberg.aimapp.utils.ResolvedImage { *; }
-keepclassmembers class org.codeberg.aimapp.utils.PartitionedImageException { *; }

-keep class org.codeberg.aimapp.utils.ShellCmd { *; }
-keep class org.codeberg.aimapp.utils.ShellArg { *; }
-keep class org.codeberg.aimapp.utils.RootShell { *; }

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
