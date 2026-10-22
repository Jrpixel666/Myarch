# The engine resolves apktool-lib through reflection, so keep brut.* intact.
-keep class brut.** { *; }
-dontwarn brut.**
-keep class com.android.apksig.** { *; }
-dontwarn com.android.apksig.**
-dontwarn javax.xml.stream.**
-dontwarn org.yaml.snakeyaml.**
