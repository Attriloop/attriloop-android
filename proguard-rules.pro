# SDK-side R8 when minifyEnabled is true on this library.
# Keep only the host-callable surface. Kotlin `object` fields compile to
# public static Java fields — `public *;` would keep apiKey/prefs/etc.
# Endpoints and payload shapes stay on the wire regardless — this is friction,
# not secrecy.

-keepattributes *Annotation*, InnerClasses, Signature, Exception, EnclosingMethod

-keep class io.attriloop.sdk.Attriloop {
    public static final io.attriloop.sdk.Attriloop INSTANCE;
}
-keepclassmembers class io.attriloop.sdk.Attriloop {
    public static *** getAttriloopId(...);
    public static void getAttriloopId$annotations();
    public static *** configure(...);
    public static *** configure$default(...);
    public static *** setCustomerUserId(...);
    public static *** claimReferralCode(...);
    public static *** claimReferralCode$default(...);
    public static *** sendEvent(...);
    public static *** sendEvent$default(...);
    public static *** sendCustomEvent(...);
    public static *** sendCustomEvent$default(...);
    public static *** sendTestEvent(...);
    public static *** flushQueue();
    public static *** getAttributionParams(...);
    public static *** getMatchStatus();
    public static *** getAttribution(...);
    public static *** getPlayInstallReferrer();
    public static *** handleDeepLink(...);
    public static *** getDeepLink();
    public static *** onDeepLink(...);
    public static *** setUserAttributes(...);
    public static *** setUserAttributes$default(...);
    public static *** clearUserAttributes();
    # Suspend + callback forms (static via @JvmStatic, plus the INSTANCE bridge).
    public *** awaitAttributionParams(...);
    public *** getRevenueCatAttributes(...);
    public *** getRevenueCatAttributes$default(...);
}

# Instantiated by the framework from the merged manifest (pre-configure install id).
-keep class io.attriloop.sdk.AttriloopInitProvider { <init>(); }

-keep class io.attriloop.sdk.AttriloopEvent { *; }
-keepclassmembers enum io.attriloop.sdk.AttriloopEvent { *; }
-keep class io.attriloop.sdk.AttriloopMatchStatus { *; }
-keepclassmembers enum io.attriloop.sdk.AttriloopMatchStatus { *; }
-keep class io.attriloop.sdk.AttriloopAttribution { *; }
-keep class io.attriloop.sdk.AttriloopTestResult { *; }

-dontwarn com.android.installreferrer.**
-keep class com.android.installreferrer.** { *; }

# Kotlin/Java 9+ string concat. Not on the Android bootclasspath; R8 must not
# fail the library minify over it.
-dontwarn java.lang.invoke.StringConcatFactory
