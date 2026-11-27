-dontwarn org.bouncycastle.**
-keepclassmembers class dev.securemessenger.shared.** { <fields>; }

# JNI resolves concrete store classes and their original method descriptors.
-keep,includedescriptorclasses class * implements org.signal.libsignal.protocol.state.SignalProtocolStore {
    *;
}
