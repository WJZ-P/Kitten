plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "me.wjz.kitten"
    compileSdk = 37

    defaultConfig {
        applicationId = "me.wjz.kitten"
        // 地板定在 Android 13（API 33）。这不是保守，是三条硬约束叠出来的结果：
        //
        // API 30：无线调试本身才出现。而我们刻意不支持 root、也不碰电脑，
        //         手机内无线 ADB 是唯一的提权路径，所以没有更低的退路。
        //         （Shizuku 能压到 24，是因为它还留着 root 和电脑 adb 两条路。）
        // API 31：BinderSender 把 binder 交回 App 走的是 IContentProvider.call
        //         的 AttributionSource 签名，API 30 上签名不同。低于 31 整条
        //         特权链路根本跑不通，装上也只是个摆设。
        // API 33：开机自动恢复特权进程要靠系统记住"受信任的 WiFi 网络"，
        //         而 adb_temp_keys.xml 的解析 bug 到 Android 13 才修
        //         （AOSP change 2128832，Bug 236299256）。在那之前每次重启
        //         都会被当成陌生网络、弹框等用户确认，无人值守就无从谈起。
        //         Shizuku 的开机自启也正是卡在 TIRAMISU 这一刀。
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":privilege"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // LifecycleResumeEffect：让界面自己感知"回到前台"，不用再把逻辑放进 Activity。
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
