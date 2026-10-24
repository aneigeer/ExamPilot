# ============================================================
# R8/ProGuard 规则（release 构建）
# 目标：在开启混淆与资源压缩的前提下，保住 Retrofit/Gson 的反射链路。
# ============================================================

# ---------- 本应用的数据模型与接口声明（Gson 反射 / Retrofit 注解）----------
# 模型字段名即 JSON 键名，混淆后会解析失败，必须整类保留
-keep class com.examprep.app.data.model.** { *; }
# Retrofit 接口与请求体类（含 @Body/@Query/@Path 注解与字段名）
-keep class com.examprep.app.data.api.** { *; }
# 视图绑定生成类（ViewBinding）按 id 反射访问
-keep class com.examprep.app.databinding.** { *; }

# 保留泛型签名与注解：Gson/Retrofit 依赖它们做类型解析
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# ---------- Retrofit ----------
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# ---------- Gson ----------
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ---------- OkHttp / Okio ----------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# ---------- Glide（4.x 自带 consumer 规则，这里只消除警告）----------
-dontwarn com.bumptech.glide.**
