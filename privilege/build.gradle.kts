plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "me.wjz.kitten.privilege"
    compileSdk = 37

    defaultConfig {
        // 与 app 模块保持一致，理由见 app/build.gradle.kts 里的说明。
        // 对本模块而言最关键的是 API 31：BinderSender 只实现了 AttributionSource
        // 那条交回 binder 的路径。
        minSdk = 33
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
