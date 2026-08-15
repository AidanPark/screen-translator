import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.services)
    alias(libs.plugins.google.firebase.crashlytics)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.compose.compiler)
}

val admobAppId = if (gradle.startParameter.taskNames.any { it.contains("Debug") }) {
    "ca-app-pub-xxxxxxxxxxxxxxxx~xxxxxxxxxx"
} else {
    "ca-app-pub-xxxxxxxxxxxxxxxx~xxxxxxxxxx"
}

android {
    namespace = "com.galaxy.airviewdictionary"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.galaxy.airviewdictionary"
        minSdk = 26
        targetSdk = 36
        versionCode = 20502
        versionName = "2.5.2"
        manifestPlaceholders["ADMOB_APP_ID"] = admobAppId
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    bundle {
        abi { enableSplit = true } // ABI별로 APK를 나누기
        language { enableSplit = true } // 언어별로 APK를 나누기
        density { enableSplit = true } // 해상도별로 APK를 나누기
    }
    signingConfigs {
        create("release") {
            keyAlias = project.property("KEY_ALIAS") as String
            keyPassword = project.property("KEY_PASSWORD") as String
            storeFile = file(rootProject.file(project.property("KEYSTORE_FILE") as String))
            storePassword = project.property("KEY_PASSWORD") as String
        }
    }
    buildTypes {
        debug {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            firebaseCrashlytics {
                mappingFileUploadEnabled = true
            }
            signingConfig = signingConfigs.getByName("release")
        }
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
    }
    buildFeatures {
        compose = true
        viewBinding = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.admob)
    implementation(libs.app.review)
//    implementation(libs.app.update)

    // Architecture Components
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)

    // Jetpack Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.constraintlayout.compose)
    implementation(libs.androidx.dynamicanimation)
    implementation(libs.compose.foundation)

    implementation(libs.androidx.annotation)

    // datastore
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.datastore.core)
    implementation(libs.androidx.datastore.preferences)

    // Hilt
    implementation(libs.dagger.hilt.android)
    ksp(libs.dagger.hilt.android.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // AdMob(play-services-ads)이 전이하는 work-runtime 2.7.0은 구식 Room(2.2.5)을 동반해
    // R8 최소 규칙에서 WorkDatabase 리플렉션 생성이 깨진다 → 최신으로 명시 승격
    implementation(libs.androidx.work.runtime)

    // theme
    implementation(libs.material)
    // icons
    implementation(libs.material.icons.extended)

    implementation(libs.google.mlkit.text.recognition)
    implementation(libs.google.mlkit.text.recognition.chinese)
    implementation(libs.google.mlkit.text.recognition.devanagari)
    implementation(libs.google.mlkit.text.recognition.japanese)
    implementation(libs.google.mlkit.text.recognition.korean)
//    implementation(libs.google.gms.mlkit.text.recognition)
//    implementation(libs.google.gms.mlkit.text.recognition.chinese)
//    implementation(libs.google.gms.mlkit.text.recognition.devanagari)
//    implementation(libs.google.gms.mlkit.text.recognition.japanese)
//    implementation(libs.google.gms.mlkit.text.recognition.korean)
    implementation(libs.google.mlkit.language.id)

    implementation(libs.deepl.api)

    // firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.appcheck)
    implementation(libs.firebase.appcheck.playintegrity)
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.config)

    implementation(libs.gson)
    implementation(libs.squareup.retrofit2.retrofit)
    implementation(libs.squareup.retrofit2.converter.gson)
    implementation(libs.squareup.okhttp)
    implementation(libs.reorderable)

    implementation(libs.timber)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation(libs.androidx.monitor)
}










