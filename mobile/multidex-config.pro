# Application class initialization happens before MultiDexApplication.attachBaseContext.
-keep class com.shilapi.xcertplay.DiPlayApplication { *; }
-keep class com.shilapi.xcertplay.DiPlayApplication$* { *; }
-keep class kotlin.jvm.internal.DefaultConstructorMarker { *; }
-keep class androidx.multidex.** { *; }
-keep class com.shilapi.xcertplay.LoggingGate { *; }
-keep class com.shilapi.xcertplay.FloatingDotOverlay { *; }
# src10: USB default-grant helper invoked as root via /system/bin/app_process, which
# only loads the APK's primary dex. Must stay in classes.dex (not a secondary dex).
-keep class com.shilapi.xcertplay.usbbt.UsbDefaultGrantMain { *; }
