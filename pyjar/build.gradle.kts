/*
 * pyjar 构建脚本: 把独立 CPython 发行包 + 启动器打成 python.jar
 *
 * 用法:
 *   .\gradlew.bat distJar        # 产出 python.jar 到项目根目录
 *   .\gradlew.bat clean distJar  # 干净重建
 *
 * 任务链:
 *   fetchPython  ->  下载独立 CPython 发行包(已存在则跳过, 缓存于 build/downloads)
 *   unpackPython ->  tar 解压
 *   flattenPython->  定位运行时根目录, 摊平到 build/stage/runtimes/<platform>,
 *                    并写入 pyjar/runtime-version.txt
 *   jar          ->  编译启动器(目标 JVM 17)并把 stage 内容一起打包
 *   distJar      ->  把 build/libs/python.jar 复制到项目根目录(交付物)
 */
import org.gradle.api.tasks.Exec

// ---- 可调参数: 内嵌的 CPython 发行包(python-build-standalone 官方独立构建) ----
val platform = "windows-x64"
val asset = "cpython-3.12.14+20260901-x86_64-pc-windows-msvc-install_only.tar.gz"
val assetUrl = "https://github.com/astral-sh/python-build-standalone/releases/download/20260901/" +
    "cpython-3.12.14%2B20260901-x86_64-pc-windows-msvc-install_only.tar.gz"
val pyVersion = Regex("cpython-(\\d+\\.\\d+\\.\\d+)\\+").find(asset)!!.groupValues[1]

val pythonArchive = layout.buildDirectory.file("downloads/$asset").get().asFile

plugins {
    java
}

java {
    // 本机 JDK 21 运行构建; 产出的字节码保持 17, 与"只需一个 JVM"的定位一致
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 17
}

// 1/5 下载发行包
val fetchPython = tasks.register<Exec>("fetchPython") {
    group = "pyjar"
    description = "下载独立 CPython 发行包到 build/downloads"
    onlyIf { !pythonArchive.exists() }
    doFirst {
        pythonArchive.parentFile.mkdirs()
    }
    commandLine("curl", "-L", "--fail", "--retry", "8", "--retry-all-errors", "--connect-timeout", "30",
        "-o", pythonArchive.toString(), assetUrl)
}

// 2/5 解压
val unpackPython = tasks.register<Exec>("unpackPython") {
    group = "pyjar"
    description = "解压 CPython 发行包到 build/extract"
    dependsOn(fetchPython)
    doFirst {
        val extractDir = layout.buildDirectory.dir("extract").get().asFile
        delete(extractDir)
        extractDir.mkdirs()
    }
    commandLine("tar", "-xf", pythonArchive.toString(), "-C", layout.buildDirectory.dir("extract").get().asFile.toString())
}

// 3/5 摊平: 找到运行时根目录, 复制为 runtimes/<platform>, 写入版本标记
val flattenPython = tasks.register<Copy>("flattenPython") {
    group = "pyjar"
    description = "把 CPython 运行时摊平到 runtimes/<platform> 布局"
    dependsOn(unpackPython)
    val stageDir = layout.buildDirectory.dir("stage").get().asFile
    // 懒解析: unpackPython 执行完才会去 extract/ 里找 python 安装根。
    // 注意树里有多个 python.exe(Lib/venv/scripts/nt/ 下还有个 venv 模板),
    // 真正的安装根旁边有 python*.dll —— 优先按此区分, 兜底取路径最短的。
    val pyRootProvider = layout.buildDirectory.dir("extract").map { extract ->
        val candidates = fileTree(extract.asFile) { include("**/python.exe") }.files
        val dll = Regex("python\\d+\\.dll")
        val rootExe = candidates.firstOrNull { c ->
            c.parentFile.listFiles()?.any { dll.matches(it.name) } == true
        } ?: candidates.minByOrNull { it.path.length }!!
        rootExe.parentFile
    }
    from(pyRootProvider) { into("runtimes/$platform") }
    into(layout.buildDirectory.dir("stage"))
    doFirst {
        delete(stageDir)
        val verFile = File(stageDir, "pyjar/runtime-version.txt")
        verFile.parentFile.mkdirs()
        verFile.writeText(pyVersion)
    }
}

// 4/5 打 jar: 编译后的类 + stage(运行时 + 版本标记), Main-Class = PyJar
// 产物直接写到项目根目录(python.jar), 与 python.cmd / site-packages 同级
tasks.jar {
    group = "pyjar"
    dependsOn(flattenPython)
    archiveFileName.set("python.jar")
    destinationDirectory.set(layout.projectDirectory)
    manifest {
        attributes["Main-Class"] = "PyJar"
        attributes["Implementation-Title"] = "pyjar"
        attributes["Implementation-Version"] = pyVersion
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(layout.buildDirectory.dir("stage"))
}
