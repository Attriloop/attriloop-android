# Keep the public SDK surface for host apps.
-keep class io.attriloop.sdk.** { public *; }

# Google Play Install Referrer (reflectively used by the AIDL client).
-keep class com.android.installreferrer.** { *; }
