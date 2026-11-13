# Gurux DLMS objects are serialized and reflected over by the engine.
-keep class gurux.dlms.objects.** { *; }
-keep class gurux.dlms.enums.** { *; }
-keep class gurux.dlms.GXDLMSClient { *; }
-keep class gurux.dlms.secure.GXDLMSSecureClient { *; }
-keep class gurux.dlms.GXReplyData { *; }
-keep class gurux.dlms.GXByteBuffer { *; }
-keep class gurux.dlms.GXDateTime { *; }

-dontwarn gurux.dlms.**
