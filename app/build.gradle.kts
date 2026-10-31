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
    // viewModel() ：让每个页面自己持有 ViewModel，不用从 Activity 层层往下传。
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    // 调 LLM 的 HTTP 客户端。JSON 走 Android 内置的 org.json，不额外引库——
    // kotlinx.serialization 的 runtime 版本要和 Kotlin 编译器插件对齐，而 LLM 的报文
    // 结构并不复杂，手写解析换一个"不会因为升 Kotlin 而编译失败"更划算。
    implementation(libs.okhttp)
    // 液体玻璃效果。它是 KMP 库且依赖 org.jetbrains.compose.*，在 Android 上会解析到
    // 我们已有的 androidx.compose——由 compose-bom 统一版本，不会各拉一份。
    implementation(libs.backdrop)
    // backdrop 把 shapes 声明成 implementation（发布的 POM 里是 runtime scope），所以它只在
    // 运行时 classpath 上，编译期看不到——不显式依赖的话 `com.kyant.shapes.Capsule` 会报
    // Unresolved reference。
    implementation(libs.shapes)
    // 模型回复的 Markdown。core 是解析和排版（底下是 JetBrains 的 Markdown 解析器），-m3 是 Material 3 的
    // 默认配色、字号和任务列表的勾选框。加载图片（-coil）没引：模型给的图片链接不去下载。
    implementation(libs.markdown.renderer)
    implementation(libs.markdown.renderer.m3)
    // 代码块的语法高亮引擎。没用 markdown 库的 -code 模块：它在流式时会在"没上色""上了色"之间来回闪，
    // 代码块自己画（ui/chat/ChatCodeBlock），只借它底下的这个引擎。
    // 它的结果类型标了 @Serializable，所以会带进 kotlinx-serialization 的运行时（连同引擎本身，jar 加起来
    // 七八百 KB，没开混淆时包会大这么多）。上面说不引 kotlinx.serialization 是怕它的编译器插件和 Kotlin 版本
    // 对不上；这里只是带进运行时，我们自己不用 @Serializable，不涉及插件。
    implementation(libs.highlights)
    // 回复里的数学公式。renderer 是排版（会带上它的 parser、base）；fonts-katex 是配套的 KaTeX 数学字体，
    // 排版用的尺寸参数是按这套字体定的，换别的字体符号会错位，作者也强烈建议带上。加起来约 1.7MB（排版、
    // 解析各五百多 KB，字体六百多 KB）。只在 ui/chat/ChatMath.kt 里用。
    implementation(libs.latex.renderer)
    implementation(libs.latex.fonts.katex)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
