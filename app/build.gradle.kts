import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.dsh.miuix.cardface"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "io.github.miambao.cardface"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.1"
    }

    // 签名信息从本地的 keystore.properties 读取（该文件与 keystore/ 都不入库）。
    // 开源仓库不带签名文件：没有 keystore.properties 时 release 产物为未签名 APK，
    // 需要自己生成 keystore 后再打可安装包。
    val keystorePropsFile = rootProject.file("keystore.properties")
    val keystoreProps = Properties().apply {
        if (keystorePropsFile.isFile) keystorePropsFile.inputStream().use { load(it) }
    }

    signingConfigs {
        create("release") {
            if (keystorePropsFile.isFile) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile", "keystore/cardface.jks"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystorePropsFile.isFile) signingConfigs.getByName("release") else null
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    lint { abortOnError = false; checkReleaseBuilds = false }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-icons:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-preference:0.9.4")
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation(project(":imaging"))
    implementation("org.luckypray:dexkit:2.3.0")
    compileOnly("io.github.libxposed:api:101.0.1")
    testImplementation("junit:junit:4.13.2")
}
