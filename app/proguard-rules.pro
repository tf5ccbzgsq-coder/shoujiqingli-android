# 月历 App ProGuard 规则
# release 目前未开启混淆（minifyEnabled false），此文件为构建占位与后续启用混淆时备用。

# 腾讯 QQ 开放 SDK（open_sdk lite）：内部大量反射，禁止混淆
-keep class com.tencent.** { *; }
-dontwarn com.tencent.**

# 保留 CrashHandler / 反射用到的入口
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*
