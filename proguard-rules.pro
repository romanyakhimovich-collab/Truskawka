# R8 rules for the release build (on top of proguard-android-optimize.txt).

# QR scanning (zxing-android-embedded): the capture activity and decoder are created
# through the manifest/intents and the library looks some classes up by name.
-keep class com.journeyapps.barcodescanner.** { *; }
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**
-dontwarn com.journeyapps.barcodescanner.**

# Crypto uses the platform JCA providers (Ed25519 / X25519 / AES-GCM) by algorithm name;
# nothing of ours is reflected on, so no extra keeps are needed for mesh.*.

# Readable stack traces from user crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
