# R8 rules for "המחותן". Names are kept (no obfuscation) so crash reports stay readable.
-dontobfuscate
# No optimization/class merging: behaviour stays identical to the source and crash reports stay readable.
-dontoptimize
-keepattributes SourceFile,LineNumberTable,*Annotation*

# Entry points declared in the manifest (aapt2 also generates rules for these).
-keep class il.hamechutan.app.platform.App { <init>(); }
-keep class il.hamechutan.app.ui.MainActivity { <init>(); }
-keep class il.hamechutan.app.platform.ReminderReceiver { <init>(); }
-keep class il.hamechutan.app.platform.SystemEventsReceiver { <init>(); }
-keep class il.hamechutan.app.platform.DocProvider { <init>(); }

# Enum valueOf()/values() are used for values stored in the database.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-dontwarn kotlin.**
-dontwarn org.jetbrains.annotations.**
-dontwarn javax.annotation.**
