# kotlinx.serialization keeps generated serializers via its bundled consumer rules.
# Ktor / OkHttp optional dependencies
-dontwarn org.slf4j.**

# LiteRT-LM discovers these tools and their optional parameters through Kotlin
# reflection. Keep their names, annotations, metadata, and default-argument
# bridges so the optimized APK exposes the same tools as the debug build.
-keep class dev.hermeskotlin.android.local.LocalImageTools { *; }
-keep class dev.hermeskotlin.android.local.LocalFolderTools { *; }
-keep class kotlin.Metadata { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Comments on a selection read every text in the selection container by this name (CommentSelection.android.kt).
-keepclassmembers class androidx.compose.foundation.text.selection.SelectionState {
    public java.util.List getSelectableTexts();
}

# WebRTC (GPT-Live voice calls) is reached from native code by name, and its AAR ships no rules of its own.
# Native code calls only what it marks @CalledByNative (the default rules keep native methods' names), so keep
# those members, their classes and the types in their signatures; R8 may shrink and rename the rest.
-keepclasseswithmembers,includedescriptorclasses class org.webrtc.** {
    @org.webrtc.CalledByNative <init>(...);
}
-keepclasseswithmembers,includedescriptorclasses class org.webrtc.** {
    @org.webrtc.CalledByNative <methods>;
}
# Nothing in 150.7871.01 uses the unchecked form; it's here for a WebRTC update that does.
-keepclasseswithmembers,includedescriptorclasses class org.webrtc.** {
    @org.webrtc.CalledByNativeUnchecked <methods>;
}
-dontwarn org.webrtc.**
# Its JNI glue too: JNI_OnLoad finds these by name, and renamed they abort the app as a call starts.
-keep class org.jni_zero.** { *; }
-dontwarn org.jni_zero.**

# TeX environments expand through NewCommandMacro, loaded and invoked by name in MacroInfo.
-keep class org.scilab.forge.jlatexmath.NewCommandMacro {
    public <init>();
    public java.lang.String executeMacro(org.scilab.forge.jlatexmath.TeXParser, java.lang.String[]);
}
