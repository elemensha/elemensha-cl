# ============================================================================
#  R8 / ProGuard 규칙
# ============================================================================

# ---------------------------------------------------------- kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.elemensha.copy.** {
    *** Companion;
    <fields>;
}
-keepclasseswithmembers class com.elemensha.copy.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.elemensha.copy.**$$serializer { *; }
# @Serializable 데이터 클래스는 필드명이 JSON 키이므로 이름을 보존한다
-keepclassmembers @kotlinx.serialization.Serializable class com.elemensha.copy.** {
    <fields>;
}

# ---------------------------------------------------------------------- OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ------------------------------------------------- androidx.security-crypto / Tink
# security-crypto 는 Google Tink 를 쓰고, Tink 는 errorprone 어노테이션을
# 참조한다. errorprone 은 '컴파일 전용' 의존성이라 APK 에 들어가지 않으므로
# R8 이 "Missing class" 로 판단해 빌드를 중단시킨다.
# 런타임에 필요 없는 어노테이션이므로 무시해도 안전하다.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn com.google.api.client.**
-dontwarn com.google.auto.value.**
# Tink 의 KeysDownloader 는 원격 키셋을 받아올 때만 joda-time 을 쓴다.
# 우리는 로컬 키스토어만 쓰므로 이 코드 경로는 실행되지 않는다.
-dontwarn org.joda.time.**
-keep class com.google.crypto.tink.** { *; }
-keep class androidx.security.crypto.** { *; }

# ------------------------------------------------------------------- Compose
# Compose 는 자체 규칙을 라이브러리에 포함하지만, 리플렉션으로 접근하는
# 프리뷰/툴링 클래스에서 경고가 남는다.
-dontwarn androidx.compose.**

# ------------------------------------------------------------------ 로그 제거
# 릴리스 빌드에서 디버그 로그 호출을 통째로 걷어낸다 (용량·성능)
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
