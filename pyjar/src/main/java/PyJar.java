import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * pyjar: Python 被塞进 jar 之后的启动器。
 *
 * 一个 jar 只做三件事:
 *   1. 探测当前平台/架构, 从 jar 资源 runtimes/<platform>/ 中选出内置的 CPython;
 *   2. 首次运行时把运行时解压到缓存目录(按 jar 内容哈希作 key, 只解压一次);
 *   3. 把全部参数原样转发给内置 python, 透传 stdio 与退出码。
 *
 * 第三方包策略: jar 同级的 site-packages/ 文件夹。
 *   - PYTHONPATH 指向它 -> import 能找到
 *   - PIP_TARGET 指向它 -> pip install 默认装进它
 * 所以 python.jar + site-packages/ + 脚本 拷到任何一台有 JVM 的机器都能跑。
 */
public class PyJar {

    private static final String RUNTIMES = "runtimes/";
    private static final long T0 = System.nanoTime();
    private static final boolean DEBUG = System.getenv("PYJAR_DEBUG") != null;

    private static void dbg(String s) {
        if (DEBUG) {
            System.err.println("[pyjar-dbg " + (System.nanoTime() - T0) / 1_000_000 + "ms] " + s);
        }
    }

    public static void main(String[] args) throws Exception {
        dbg("enter main");
        Path jar = locateJar();
        dbg("jar located: " + jar);

        List<String> passthrough = new ArrayList<String>();
        for (String a : args) {
            if (a.equals("--pyjar-clean-cache")) {
                cleanCache(jar);
                return;
            }
            passthrough.add(a);
        }

        // 便捷别名: java -jar python.jar pip install X  ==  python -m pip install X
        // (若当前目录真有叫 pip 的脚本, 用 ./pip 显式路径即可绕过)
        if (!passthrough.isEmpty() && (passthrough.get(0).equals("pip") || passthrough.get(0).equals("pip3"))) {
            passthrough.set(0, "-m");
            passthrough.add(1, "pip");
        }

        String platform = platform();
        String version = readEmbeddedText("/pyjar/runtime-version.txt", "unknown");
        dbg("platform=" + platform + " version=" + version);

        // 1. 解压内置运行时到缓存(内容哈希作 key, 一次即可)
        Path cacheRoot = cacheRoot(jar);
        dbg("cacheRoot computed");
        if (!Files.exists(cacheRoot.resolve(".pyjar-ok"))) {
            long t0 = System.currentTimeMillis();
            System.err.println("pyjar: first run, extracting embedded CPython " + version
                    + " (" + platform + ") to cache ...");
            extractRuntime(jar, platform, cacheRoot);
            Files.write(cacheRoot.resolve(".pyjar-ok"), new byte[0]);
            System.err.println("pyjar: done in " + (System.currentTimeMillis() - t0) + " ms");
        }

        // 2. 找 python 可执行文件(构建时已把运行时的根目录摊平到 runtimes/<platform>/ 下)
        Path exe = findPython(cacheRoot);
        dbg("python exe resolved");
        if (exe == null) {
            System.err.println("pyjar: no python executable under " + cacheRoot);
            System.exit(71);
            return;
        }

        // 3. 准备 jar 同级的 site-packages(项目的"可移动依赖区")
        Path jarDir = jar.getParent() != null
                ? jar.getParent().toAbsolutePath().normalize()
                : Paths.get(".").toAbsolutePath().normalize();
        Path sitePkgs = jarDir.resolve("site-packages");
        dbg("site-packages path computed");
        try {
            Files.createDirectories(sitePkgs);
            warnOnVersionMismatch(sitePkgs, version);
        } catch (IOException e) {
            System.err.println("pyjar: cannot prepare " + sitePkgs + ": " + e.getMessage());
            System.exit(72);
            return;
        }

        // 4. 环境变量: 依赖区接入 import 与 pip, 默认 UTF-8 输出
        Map<String, String> env = new java.util.HashMap<String, String>(System.getenv());
        prependPath(env, "PYTHONPATH", sitePkgs.toString());
        env.put("PIP_TARGET", sitePkgs.toString());
        if (env.get("PYTHONUTF8") == null) {
            env.put("PYTHONUTF8", "1");
        }

        // 5. 原样转发
        List<String> cmd = buildSpawnCommand(exe, passthrough, platform, cacheRoot);
        dbg("command assembled: " + cmd);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().clear();
        pb.environment().putAll(env);
        pb.inheritIO();

        Process child;
        try {
            child = pb.start();
            dbg("child started");
        } catch (IOException e) {
            System.err.println("pyjar: failed to launch " + exe + ": " + e.getMessage());
            System.exit(73);
            return;
        }
        int code = child.waitFor();
        dbg("child exited code=" + code);
        System.exit(code);
    }

    // ---------------------------------------------------------------- 定位自身

    private static Path locateJar() throws Exception {
        java.net.URL loc = PyJar.class.getProtectionDomain().getCodeSource().getLocation();
        if (loc == null) {
            System.err.println("pyjar: cannot locate own jar");
            System.exit(70);
        }
        Path p = Paths.get(loc.toURI());
        if (Files.isDirectory(p)) {
            System.err.println("pyjar: running from classes directory is not supported; use java -jar python.jar");
            System.exit(70);
        }
        return p.toAbsolutePath().normalize();
    }

    // ---------------------------------------------------------------- 平台/架构

    private static String platform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String osName;
        if (os.contains("win")) osName = "windows";
        else if (os.contains("mac") || os.contains("darwin")) osName = "macos";
        else if (os.contains("linux")) osName = "linux";
        else osName = os.replaceAll("[^a-z0-9]", "");
        String archName;
        if (arch.contains("aarch64") || arch.contains("arm64")) archName = "arm64";
        else if (arch.equals("amd64") || arch.contains("x86_64") || arch.contains("x64")) archName = "x64";
        else if (arch.contains("86")) archName = "x86";
        else archName = arch;
        return osName + "-" + archName;
    }

    // ---------------------------------------------------------------- 缓存目录

    private static Path cacheRoot(Path jar) throws Exception {
        String override = System.getenv("PYJAR_CACHE");
        Path base;
        if (override != null && !override.isEmpty()) {
            base = Paths.get(override);
        } else if (System.getenv("LOCALAPPDATA") != null) {
            base = Paths.get(System.getenv("LOCALAPPDATA"), "pyjar", "cache");
        } else {
            String home = System.getProperty("user.home", ".");
            base = isWindows()
                    ? Paths.get(home, "AppData", "Local", "pyjar", "cache")
                    : Paths.get(home, ".cache", "pyjar");
        }
        return base.resolve(jarHash16(jar));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String jarHash16(Path jar) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(jar)) {
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) > 0) {
                md.update(buf, 0, r);
            }
        }
        byte[] d = md.digest();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            sb.append(String.format("%02x", d[i] & 0xff));
        }
        return sb.toString();
    }

    private static void cleanCache(Path jar) throws Exception {
        Path cache = cacheRoot(jar);
        if (Files.exists(cache)) {
            deleteRecursively(cache);
        }
        System.err.println("pyjar: cache cleared: " + cache);
    }

    // ---------------------------------------------------------------- 解压运行时

    private static void extractRuntime(Path jar, String platform, Path cacheRoot)
            throws IOException {
        String prefix = RUNTIMES + platform + "/";
        Files.createDirectories(cacheRoot);
        boolean found = false;
        try (JarFile jf = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> en = jf.entries();
            while (en.hasMoreElements()) {
                JarEntry e = en.nextElement();
                String n = e.getName();
                if (!n.startsWith(prefix)) {
                    continue;
                }
                found = true;
                if (e.isDirectory()) {
                    continue;
                }
                String rel = n.substring(prefix.length());
                Path out = cacheRoot.resolve(rel);
                Path parent = out.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                try (InputStream in = jf.getInputStream(e)) {
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        if (!found) {
            System.err.println("pyjar: this jar has no " + platform + " runtime inside.");
            System.err.println("pyjar: embedded runtimes: " + embeddedPlatforms(jar));
            System.exit(74);
        }
    }

    /** 列出 jar 内已内置的平台, 供报错提示用。 */
    private static Set<String> embeddedPlatforms(Path jar) throws IOException {
        Set<String> out = new LinkedHashSet<String>();
        try (JarFile jf = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> en = jf.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName();
                if (n.startsWith(RUNTIMES)) {
                    String rest = n.substring(RUNTIMES.length());
                    int slash = rest.indexOf('/');
                    if (slash > 0) {
                        out.add(rest.substring(0, slash));
                    }
                }
            }
        }
        return out;
    }

    private static Path findPython(Path cacheRoot) {
        String[] candidates = {
            "python.exe", "python3.exe", "python3",
            "python/python.exe", "python/python3.exe", "python/python3"
        };
        for (String c : candidates) {
            Path p = cacheRoot.resolve(c);
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- 起进程

    /**
     * Windows 上的实测: 由 java.exe 直接拉起解释器会被企业 EDR 视作可疑链
     * (java -> 可写目录里的 python), 首次进程创建被拖慢 2~3 秒;
     * 改经 cmd.exe -> 包装脚本 -> python 则不受影响(~50ms 创建)。
     *
     * 注意不能把 python 及参数直接拼进 cmd /c 字符串: Java 会按它的规则转义
     * 内嵌引号(反斜杠), 而 cmd 不认识 \" 转义。所以 python 的启动被固化在
     * cache 根目录的 pyjar-bridge.cmd 里(%~dp0python.exe %*), 可变参数作为
     * 独立 argv 传给 cmd, 由 Java 对含空格参数加干净的双引号, 再由 %* 原样转发。
     *
     * 参数含 cmd 敏感字符(引号/百分号/换行)或为空/超长时, 回退直连, 正确性优先。
     */
    private static List<String> buildSpawnCommand(Path exe, List<String> args,
            String platform, Path cacheRoot) throws IOException {
        if (!platform.startsWith("windows")) {
            List<String> direct = new ArrayList<String>();
            direct.add(exe.toString());
            direct.addAll(args);
            return direct;
        }
        Path bridge = cacheRoot.resolve("pyjar-bridge.cmd");
        String bridgePath = bridge.toString();
        boolean bridgeable = bridgePath.indexOf('"') < 0 && bridgePath.indexOf('%') < 0;
        int len = bridgePath.length() + 8;
        for (String arg : args) {
            if (arg.isEmpty() || arg.indexOf('"') >= 0 || arg.indexOf('%') >= 0
                    || arg.indexOf('\r') >= 0 || arg.indexOf('\n') >= 0) {
                bridgeable = false;
                break;
            }
            len += arg.length() + 2;
        }
        if (!bridgeable || len > 7900) {
            List<String> direct = new ArrayList<String>();
            direct.add(exe.toString());
            direct.addAll(args);
            return direct;
        }
        // 确保桥接脚本存在: @echo off / "%~dp0python.exe" %* / exit /b %errorlevel%
        String script = "@echo off\r\n\"%~dp0python.exe\" %*\r\nexit /b %errorlevel%\r\n";
        Files.write(bridge, script.getBytes(java.nio.charset.StandardCharsets.US_ASCII));

        List<String> bridgeCmd = new ArrayList<String>();
        String comSpec = System.getenv("ComSpec");
        if (comSpec == null || comSpec.isEmpty()) {
            comSpec = "C:\\Windows\\System32\\cmd.exe";
        }
        bridgeCmd.add(comSpec);
        bridgeCmd.add("/d");
        bridgeCmd.add("/c");
        bridgeCmd.add(bridgePath);
        bridgeCmd.addAll(args);
        return bridgeCmd;
    }

    // ---------------------------------------------------------------- site-packages

    private static void warnOnVersionMismatch(Path sitePkgs, String embedded) throws IOException {
        Path marker = sitePkgs.resolve(".pyjar-runtime");
        if (Files.exists(marker)) {
            String existing = new String(Files.readAllBytes(marker), java.nio.charset.StandardCharsets.UTF_8).trim();
            if (!sameMajorMinor(existing, embedded)) {
                System.err.println("pyjar: WARNING site-packages/ was created by CPython " + existing
                        + ", but this jar embeds " + embedded + ".");
                System.err.println("pyjar: WARNING native extensions (.pyd) will not load. "
                        + "Delete site-packages/ and reinstall your packages.");
            }
        } else {
            Files.write(marker, (embedded + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static boolean sameMajorMinor(String a, String b) {
        String pa = a.split("\\.").length >= 2 ? a.split("\\.")[0] + "." + a.split("\\.")[1] : a;
        String pb = b.split("\\.").length >= 2 ? b.split("\\.")[0] + "." + b.split("\\.")[1] : b;
        return pa.equals(pb);
    }

    // ---------------------------------------------------------------- 工具

    private static void prependPath(Map<String, String> env, String key, String value) {
        String old = env.get(key);
        env.put(key, old == null || old.isEmpty() ? value : value + File.pathSeparator + old);
    }

    private static String readEmbeddedText(String path, String dflt) {
        try (InputStream in = PyJar.class.getResourceAsStream(path)) {
            if (in == null) {
                return dflt;
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return dflt;
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
            for (Path c : ds) {
                if (Files.isDirectory(c)) {
                    deleteRecursively(c);
                } else {
                    Files.deleteIfExists(c);
                }
            }
        }
        Files.deleteIfExists(root);
    }
}
