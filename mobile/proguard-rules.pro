# 仅启用 shrink 裁剪解决 64K 方法数超限（禁止 multidex 的替代方案）：
# -dontoptimize / -dontobfuscate 关闭优化与混淆，行为与未裁剪一致，规避反射/JNI 风险

# HSAE 蓝牙模块 autosdk：运行时直连 + Binder/AIDL 反射，整包保留
-keep class com.hsae.autosdk.** { *; }

# BouncyCastle：JCA Provider 通过反射注册算法，整包保留
-keep class org.bouncycastle.** { *; }

# jmdns：mDNS 服务发现，整包保留
-keep class javax.jmdns.** { *; }

# Conscrypt：JNI 原生层按名称调用，整包保留
-keep class org.conscrypt.** { *; }

# 缺失类告警：均为 Android 平台不存在的可选依赖，运行时不会触达
# - bouncycastle 的 LDAP 证书仓库（javax.naming 是 Java SE 专属）
# - conscrypt 的 KitKat 之前平台适配器（本项目最低 API 19，不会加载）
# - jmdns 的 slf4j 运行时绑定（Android 上走内置 fallback）
-dontwarn javax.naming.**
-dontwarn org.apache.harmony.xnet.provider.jsse.**
-dontwarn org.slf4j.impl.**
-dontwarn android.os.ServiceManager
-dontwarn com.android.org.conscrypt.SSLParametersImpl

