# OkHttp and androidx.activity are compileOnly: an app without them must still pass R8.
-dontwarn okhttp3.**
-dontwarn androidx.activity.**
