plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "me.wjz.kitten.privilege"
    compileSdk = 37

    defaultConfig {
        minSdk = 30
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        // 需要 AIDL 来定义跨进程接口；AGP 9 默认关闭，这里手动打开。
        aidl = true
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.libadb.android)
    implementation(libs.sun.security.android)
    implementation(libs.conscrypt.android)
}
