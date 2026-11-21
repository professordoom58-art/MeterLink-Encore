# Release hardening for MeterLink-Encore.
#
# Rules are deliberately narrow: all obfuscation applies to the app's own code
# and the third-party entry points below are the only ones allowed to keep
# their names. Never add a wildcard keep for `com.utility.dlmsreader.**` - that
# would disable obfuscation for the whole app.

# Flatten every renamed class into one short package and let R8 widen access
# modifiers so it can inline, merge and remove unused members aggressively.
-repackageclasses 'a'
-allowaccessmodification

# Line numbers keep release crash reports readable. No library here resolves
# classes or members by name beyond the USB serial prober kept below.
-keepattributes SourceFile,LineNumberTable,Signature

# Verbose logging is removed from release builds. The app's protocol trace is
# kept in its own in-memory buffer and is unaffected.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# --- Gurux DLMS engine -------------------------------------------------------
# COSEM object instances are created from the engine's own class table and
# their scaler/unit state is read through the typed getters used by the
# catalog, so their names and members must stay stable.
-keep class gurux.dlms.objects.** { *; }
-keep class gurux.dlms.enums.** { *; }
-keep class gurux.dlms.GXDLMSClient { *; }
-keep class gurux.dlms.secure.GXDLMSSecureClient { *; }
-keep class gurux.dlms.GXReplyData { *; }
-keep class gurux.dlms.GXByteBuffer { *; }
-keep class gurux.dlms.GXDateTime { *; }
-dontwarn gurux.dlms.**

# --- USB serial transport ----------------------------------------------------
# usb-serial-for-android discovers drivers reflectively:
#   ProbeTable.addDriver -> Class.getMethod("getSupportedDevices", ProbeTable)
#   UsbSerialProber      -> Class.getConstructor(UsbDevice).newInstance(...)
# so driver classes, their UsbDevice constructor and that method name must
# survive obfuscation.
-keep class com.hoho.android.usbserial.driver.** { *; }
-keep class com.hoho.android.usbserial.util.** { *; }
-keep interface com.hoho.android.usbserial.driver.UsbSerialDriver { *; }
-keep class * implements com.hoho.android.usbserial.driver.UsbSerialDriver {
    public <init>(android.hardware.usb.UsbDevice);
    public java.util.List getSupportedDevices(com.hoho.android.usbserial.driver.ProbeTable);
}
-dontwarn com.hoho.android.usbserial.**

# --- Saved report models -----------------------------------------------------
# MeterReportSerializer and ExternalReadingBackup build their org.json
# documents by hand with explicit property names and never reflect over the
# model classes, so MeterDataReport/ProfileSection/RegisterReading and friends
# are intentionally not kept: they shrink and obfuscate like the rest of the
# app and JSON/GZIP round-trips are unaffected. The field names written to
# storage are literals in the serializer, not member names.

# FileProvider (used by the APK self-updater) is referenced from the manifest;
# AGP's manifest keep rules already cover it.
