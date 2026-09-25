# ============================================================
# Screen Translate — R8 rules (최소 구성)
#
# 원칙: AndroidX / Firebase / ML Kit / Retrofit / OkHttp / Gson /
# Coroutines / Hilt / Compose / Coil / ExoPlayer 등 최신 라이브러리는
# AAR에 consumer ProGuard 규칙을 내장하고 있으므로 블랭킷 keep을 하지 않는다.
# (블랭킷 keep은 최적화/난독화/축소율을 크게 떨어뜨림 — Play Console 권장 조치)
#
# 앱 코드에서 리플렉션이 실제로 쓰이는 지점만 명시적으로 보호한다.
# ============================================================

# ── 크래시 리포트 역난독화 (mapping.txt는 Crashlytics/Play에 업로드됨) ──
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── Gson 리플렉션 메타데이터 (TypeToken 제네릭, 어노테이션) ──
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# ── Retrofit suspend 응답 타입 보존 ──
# R8 full mode 에서 Continuation 제네릭 시그니처가 소거되면 Gson 컨버터가
# LinkedTreeMap 을 돌려주고 모델 캐스팅에서 ClassCastException 이 난다.
# (2.6.x Gemini 무응답 버그의 원인 — retrofit 내장 규칙이 AGP9 에서 미적용됨)
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# ── 앱 JSON 모델: @SerializedName 없이 필드명 그대로 직렬화하므로 필드 보존 필수 ──
# (Claude/Gemini/OpenAI request·response, TranslationResponse, Transaction 등)
-keep class com.galaxy.airviewdictionary.data.remote.translation.** { <fields>; }
-keepclassmembers class com.galaxy.airviewdictionary.data.remote.geolocale.** {
    <fields>;
}

# ── Room / WorkManager: 생성된 *_Impl DB 클래스를 리플렉션으로 인스턴스화 ──
# (AdMob 전이 의존성 work-runtime 포함 — 구버전 room의 consumer 규칙 공백 방어)
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# ── DeepL 공식 라이브러리 (내부적으로 Gson 리플렉션 사용, 자체 규칙 미제공) ──
-keep class com.deepl.api.** { *; }
-dontwarn com.deepl.api.**

# ── 릴리스 빌드에서 Timber 로그 호출 제거 ──
-assumenosideeffects class timber.log.Timber {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}
-assumenosideeffects class timber.log.Timber$Tree {
    public *** d(...);
    public *** v(...);
    public *** i(...);
    public *** w(...);
    public *** e(...);
}

# ONNX Runtime(PP-OCRv5 추론) — 네이티브 코드가 JNI 로 이 클래스들을 이름으로 찾는다. AAR 에 소비자 규칙이 없다
-keep class ai.onnxruntime.** { *; }

# ── Play In-App Review (review-ktx) ──
# review-ktx 의 SAM 변환이 play-services-basement 의 컴파일 전용 애노테이션을 참조한다. 런타임에는 없어도 된다.
-dontwarn com.google.android.gms.common.annotation.NoNullnessRewrite
