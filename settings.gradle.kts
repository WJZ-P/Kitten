pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Google 的仓库只用来找 Google 自家的包（AndroidX、Material、com.google.*），和上面 pluginManagement
        // 里一样。不加这层过滤的话，每个第三方包 Gradle 都先去 dl.google.com 问一圈：那边连不上（握手失败）
        // 时直接报错，不会退到 Maven Central 去找，明明 Maven Central 上就有。
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Kitten"
include(":app")
include(":privilege")
