# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# libxposed 通过 META-INF/xposed/java_init.list 中的类名字符串加载模块入口；
# 允许入口类混淆时，需要同步改写 java_init.list，避免 release 裁剪后模块失效。
-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

# Compose 编译器生成的 Composable Lambda 与 Composable 主体依赖运行时反射，
# R8 默认规则已覆盖 androidx.compose.**；这里仅保留 miuix（KMP 库，反射面不可控）。
-keep class top.yukonga.miuix.** { *; }
-dontwarn top.yukonga.miuix.**

# libxposed service 库的 binder/Parcelable 反射需保护，避免 release 裁剪后 RemotePreferences 失效。
-keep class io.github.libxposed.service.** { *; }
-dontwarn io.github.libxposed.service.**

# 模块配置读写依赖的 SharedPreferences 与枚举键，避免 release 裁剪枚举导致 key 失配。
-keep class fuck.andes.config.** { *; }
