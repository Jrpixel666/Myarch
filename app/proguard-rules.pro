# The compositor looks up protocol messages by name in generated tables, so
# nothing in the protocol package may be renamed or stripped.
-keep class dev.myarch.wayland.protocol.** { *; }
-keepclassmembers class dev.myarch.wayland.core.** { *; }
