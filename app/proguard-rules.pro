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
# 混合时不使用大小写混合，混合后的类名为小写
-dontusemixedcaseclassnames

# 这句话能够使我们的项目混淆后产生映射文件
# 包含有类名->混淆后类名的映射关系
-verbose

# 保留Annotation不混淆
-keepattributes *Annotation*,InnerClasses

# 避免混淆泛型
-keepattributes Signature

# 指定混淆是采用的算法，后面的参数是一个过滤器
# 这个过滤器是谷歌推荐的算法，一般不做更改
-optimizations !code/simplification/cast,!field/*,!class/merging/*

-flattenpackagehierarchy

#############################################
#
# Android开发中一些需要保留的公共部分
#
#############################################
# 屏蔽错误Unresolved class name
#noinspection ShrinkerUnresolvedReference

# 移除Log类打印各个等级日志的代码，打正式包的时候可以做为禁log使用，这里可以作为禁止log打印的功能使用
# 记得proguard-android.txt中一定不要加-dontoptimize才起作用
# 另外的一种实现方案是通过BuildConfig.DEBUG的变量来控制
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int i(...);
    public static int w(...);
    public static int d(...);
    public static int e(...);
}

# 保持js引擎调用的java类
-keep class * extends io.legado.app.help.JsExtensions{*;}
# 数据类
-keep class **.data.entities.**{*;}
# HighlightStyle is serialized outside data.entities; keep nested enum names for Gson.
-keep class io.legado.app.help.HighlightStyle { *; }
-keep class io.legado.app.help.HighlightStyle$** { *; }
# hutool-core hutool-crypto
-keep class
!cn.hutool.core.util.RuntimeUtil,
!cn.hutool.core.util.ClassLoaderUtil,
!cn.hutool.core.util.ReflectUtil,
!cn.hutool.core.util.SerializeUtil,
!cn.hutool.core.util.ClassUtil,
cn.hutool.core.codec.**,
cn.hutool.core.util.**{*;}
-keep class cn.hutool.crypto.**{*;}
-dontwarn cn.hutool.**
# Bouncy Castle registers provider implementations by class name.
-keep class org.bouncycastle.jce.provider.** { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.pqc.jcajce.provider.** { *; }
# BC 的 PKIX LDAP 证书路径（CrlCache / X509LDAPCertStoreSpi）引用 Java SE 专有的
# javax.naming.*，Android 平台不存在。上面 -keep org.bouncycastle.jce.provider.** 使这两个类
# 成为 R8 入口点，因而必须显式豁免。它们只服务 LDAP 在线证书吊销检查，本项目不走该路径。
# 缺失类清单见 app/build/outputs/mapping/appRelease/missing_rules.txt（AGP 自动生成）。
-dontwarn javax.naming.NamingEnumeration
-dontwarn javax.naming.NamingException
-dontwarn javax.naming.directory.Attribute
-dontwarn javax.naming.directory.Attributes
-dontwarn javax.naming.directory.DirContext
-dontwarn javax.naming.directory.InitialDirContext
-dontwarn javax.naming.directory.SearchControls
-dontwarn javax.naming.directory.SearchResult
# 缓存
-keep class **.help.CacheManager{*;}

# markwon
-dontwarn org.commonmark.ext.gfm.**

-keep class com.jayway.jsonpath.*{*;}

# LiveEventBus
-keepclassmembers class androidx.lifecycle.LiveData {
    *** mObservers;
    *** mActiveCount;
}
-keepclassmembers class androidx.arch.core.internal.SafeIterableMap {
    *** size();
    *** putIfAbsent(...);
}

## ChangeBookSourceDialog initNavigationView
-keepclassmembers class androidx.appcompat.widget.Toolbar {
    *** mNavButtonView;
}

# MenuExtensions applyOpenTint
-keepnames class androidx.appcompat.view.menu.SubMenuBuilder
-keep class androidx.appcompat.view.menu.MenuBuilder {
    *** setOptionalIconsVisible(...);
    *** getNonActionItems();
}

# FileDocExtensions.kt treeDocumentFileConstructor
-keep class androidx.documentfile.provider.TreeDocumentFile {
    <init>(...);
}

## JSOUP
-keep class org.jsoup.**{*;}
-dontwarn org.jspecify.annotations.NullMarked

# PDFBox reads outline metadata only; Android PdfRenderer renders images.
# Its optional JPEG 2000 decoder is guarded by Class.forName in JPXFilter.
-dontwarn com.gemalto.jp2.JP2Decoder

## 对外提供api
-keep class io.legado.app.api.ReturnData{*;}

# Throwable
-keepnames class * extends java.lang.Throwable
-keepclassmembernames,allowobfuscation class * extends java.lang.Throwable{*;}

-keep public class * extends android.view.View{
    *** get*();
    void set*(***);
    public <init>(android.content.Context);
    public <init>(android.content.Context, java.lang.Boolean);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}
