# OkHttp and androidx.activity are compileOnly: an app without them must still pass R8.
-dontwarn okhttp3.**
-dontwarn androidx.activity.**
-dontwarn androidx.core.app.OnNewIntentProvider
-dontwarn androidx.core.util.Consumer

# @ScreenName names automatic screen views (Lifecycle 3): keep it on the activities that carry it.
-keepattributes RuntimeVisibleAnnotations
-keep @interface com.rubicon.analytics.ScreenName
