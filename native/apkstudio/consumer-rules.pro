# apksig encodes the signature block by reflecting on annotated classes; R8 must not strip/rename them
-keep class com.android.apksig.** { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations
-dontwarn com.android.apksig.**
-dontwarn com.reandroid.**
