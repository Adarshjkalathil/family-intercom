# WebRTC's Java layer is called from native code by name; shrinking it breaks
# the peer connection at runtime in ways that are painful to diagnose.
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
