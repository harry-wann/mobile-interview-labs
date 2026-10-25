// MobileLabs：一個 App（:app），程式分成兩套 module：
//   practice/<區>  練習：你寫的程式（module 名稱 :practice-<區>）
//   solution/<區>  解答：完整的程式（module 名稱 :solution-<區>）
// 每一區（kotlin、platform、advanced…）是一個 module，單元是 module 底下的資料夾。
// 新增一區：在 practice/ 和 solution/ 底下各開一個資料夾，放 build.gradle.kts，再按 Sync 就會自動加入。
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MobileLabs"

include(":app", ":labkit")

val sectionOrder = listOf("kotlin", "platform", "advanced", "exchange", "testing", "design", "coding")
for (kind in listOf("practice", "solution")) {
    val dir = file(kind)
    if (!dir.isDirectory) continue
    dir.listFiles()!!
        .filter { File(it, "build.gradle.kts").isFile }
        .sortedBy { sectionOrder.indexOf(it.name).let { i -> if (i < 0) 99 else i } }
        .forEach {
            include(":$kind-${it.name}")
            project(":$kind-${it.name}").projectDir = it
        }
}
