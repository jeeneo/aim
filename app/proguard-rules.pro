-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

-keep class org.codeberg.dryerlint.aim.ImageProvider {
    public protected *;
}

-keep class org.codeberg.dryerlint.aim.ImageStore {
    public *;
}

-keepclassmembers enum org.codeberg.dryerlint.aim.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-keepclassmembers class org.codeberg.dryerlint.aim.MountedImage { *; }
-keepclassmembers class org.codeberg.dryerlint.aim.PartitionedImageResult { *; }
-keepclassmembers class org.codeberg.dryerlint.aim.EnvironmentStatus { *; }
-keepclassmembers class org.codeberg.dryerlint.aim.ImportedImage { *; }
-keepclassmembers class org.codeberg.dryerlint.aim.ImageInfo { *; }
-keepclassmembers class org.codeberg.dryerlint.aim.PartitionState { *; }
-keepclassmembers class org.codeberg.dryerlint.aim.utils.ShellResult { *; }
-keepclassmembers class org.codeberg.dryerlint.aim.utils.PartitionEntry { *; }
-keepclassmembers class org.codeberg.dryerlint.aim.utils.PartitionTableInfo { *; }
-keepclassmembers class org.codeberg.dryerlint.aim.utils.ResolvedImage { *; }
-keepclassmembers class org.codeberg.dryerlint.aim.utils.PartitionedImageException { *; }

-keep class org.codeberg.dryerlint.aim.utils.ShellCmd { *; }
-keep class org.codeberg.dryerlint.aim.utils.ShellArg { *; }
-keep class org.codeberg.dryerlint.aim.utils.RootShell { *; }

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
