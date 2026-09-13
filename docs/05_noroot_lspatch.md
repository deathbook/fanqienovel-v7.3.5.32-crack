# 无 root 路线：三次尝试

目标：一个**普通安装即生效**的 APK，不需要 root / Magisk / LSPosed。

结论先说：**只有 LSPatch v1.2（JingMatrix 维护分支）走通了**，
但它有难以接受的性能代价；**推荐用 LSPosed 模块**。

---

## 尝试 1：Frida gadget 重打包 —— ❌ Android 16 崩溃

思路（沿用 Lesson1 的做法）：把 arm32 的 Frida gadget 塞进 APK，
在最早的入口加载它。

### 注入点选择

`AndroidManifest.xml` 的 `android:name` 是
`com.dragon.read.base.mute.MuteApplicationStub`（Tinker 的 Mute 壳），
它继承 `com.tencent.tinker.loader.MuteApplication`，
是 Android 实例化**第一个**类——`attachBaseContext` 就是最早的 app 代码执行点。

### 补丁

```smali
.method public attachBaseContext(Landroid/content/Context;)V
    .locals 3
    .prologue
    invoke-static {p1}, Lcom/deathbook/fanqie/crack/noroot/Bootstrap;->init(Landroid/content/Context;)V
    const-string v2, "fqgadget"
    invoke-static {v2}, Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V
    ...
```

`Bootstrap` 把 `assets/fanqie_crack.js` 解到 files 目录
（gadget 读不了 APK asset，而 config 必须用**绝对路径**指脚本）。

### 两个踩到的坑

**坑 A：`p0` 不行，要用 `p1`。**
`ContextWrapper.getFilesDir()` 委派给 `mBase`，而
Tinker 的 `MuteApplication.attachBaseContext` **不会**链式调用
`ContextWrapper.attachBaseContext`，所以 `mBase` 始终是 null：

```
NullPointerException: Attempt to invoke virtual method
  'java.io.File android.content.Context.getFilesDir()' on a null object reference
```

这个报错既没提 Bootstrap 也没提 `mBase`，很难猜。用传入的 base Context（`p1`）就对了。

**坑 B：陈旧产物。**
改完 smali 没重跑 apktool 汇编，打包脚本照旧用上一次的 `.dex`，
于是得到字节相同的 APK 和「改了但没效果」的结论。
`scripts/repack_noroot.ps1` 现在会比较 smali 与 dex 的时间戳，落后就自动重编。

### 结果

`Bootstrap` 成功执行：

```
I FanQieCrack: unpacked fanqie_crack.js -> /data/user/0/com.dragon.read/files/fanqie_crack.js.tmp (13947 bytes)
```

gadget 与 config 都正确落盘，然后：

```
F libc: Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x24
F DEBUG: backtrace: #00 pc 0052227a .../lib/arm/libfqgadget.so
```

已排除：ABI（确认是 ELF32/ARM，`e_machine=40`）、config 缺失（确认落盘且 JSON 正确）、
脚本路径（Bootstrap 已写出）。剩余怀疑是 **frida-gadget 17.x 与 Android 16 的兼容性**。
`work/downloads/` 里备了 16.6.6 / 17.2.17 待 A/B，或改用 `interaction.type = listen` 由 PC attach。

---

## 尝试 2：LSPatch v0.6（官方）—— ❌ Android 16 不可用

```powershell
java -jar lspatch-v0.6-398.jar original.apk -m module.apk -o out -l 2 -f
```

打包、签名、安装都成功，启动即失败：

```
E LSPosed : Failed to init lsplant
E LSPosed : Hook Fails: _ZN3art12ProfileSaver20ProcessProfilingInfoEbPt
E com.dragon.read: hiddenapi: Accessing hidden field
    Landroid/app/ActivityThread$AppBindData;->compatInfo:... denied
E LSPatch : java.lang.NoSuchFieldError: android.app.ActivityThread$AppBindData#compatInfo
    at org.lsposed.lspatch.loader.LSPApplication.createLoadedApkWithContext(LSPApplication.java:108)
```

两个都是硬伤：
* `lsplant` 在 Android 16 的 ART 上初始化不了
* `AppBindData.compatInfo` 字段在 Android 16 被**移除**

（Lesson1 的答案里也有同样的实测记录，`LSPatch_Probe_Result.md`。）

---

## 尝试 3：LSPatch v1.2（JingMatrix 维护分支）—— ✅ 跑通

```powershell
java -jar lspatch-v1.2-487-release.jar work\original.apk `
     -m dist\FanQieNovelCrack-lsposed-v1.0.apk -o dist\lspatch12 -l 3 -f
```

真机（Android 16）实测：

```
I LSPatch-MetaLoader: Bootstrap loader from embedment
D nativeloader: Load .../assets/lspatch/so/armeabi-v7a/liblspatch.so ... : ok
I LSPatch : Use manager: false
I LSPatch : Signature bypass level: 3
I LSPatch : hooked app initialized: android.app.LoadedApk@9dd5502
V VectorLegacyBridge: Loading legacy module com.deathbook.fanqie.crack from ...
V VectorLegacyBridge:   Loading class com.deathbook.fanqie.crack.FanQieCrackModule
I LSPatch : Modules initialized
I FanQieCrack: ================ FanQieNovelCrack attached ================
I FanQieCrack: SELFTEST hooks_installed=57 hooks_missed=0 took_us=40384
```

**57 个 hook 全部装上，Android 16 上可用。没改一行模块代码——同一份 APK 直接内嵌。**

### 为什么必须做签名绕过

见 `01_protection_analysis.md` 末节：服务端对改包客户端返回错误码 110。
`-l 3` = `pm + openat + svc`，会插桩 native 层让 App 读到**原始 APK**。

### 代价：卡

`-l 3` 的说明原文：

> instruments raw-syscall apk reads; patches native code, so an app that verifies
> its own code may detect it -- prefer 2 unless it is not enough

每次读 APK 都要过一遍插桩，对番茄这种**每秒都在读资源**的 App 是灾难。
实测「特别卡，几乎无法正常使用」。

**所以：要用得舒服，请用 LSPosed 模块（APK 不动，无签名绕过开销）。**

---

## 附带产物：纯 smali 补丁版 —— ⚠️ 能跑但拿不到内容

思路：完全不引入任何运行时框架，只做 4 处 smali 补丁 + 1 个新 dex。

关键技巧：**`PrivilegeManager` 不是 final**，`getInstance()` 里只有一处
`new-instance`，把它指向子类即可：

```smali
new-instance v1, Lcom/deathbook/fanqie/crack/noroot/CrackPrivilegeManager;
invoke-direct {v1}, Lcom/deathbook/fanqie/crack/noroot/CrackPrivilegeManager;-><init>()V
sput-object v1, L.../PrivilegeManager;->k:L.../PrivilegeManager;
```

单例同时存进静态字段 `k`，所以**工厂和字段两个入口都拿到子类**。
13 个 native 方法由普通 Java 方法覆盖（合法），无需碰 `.so`。

补丁清单：

| dex | 目标 |
|---|---|
| `classes2` | `NsAdDependImpl.isReaderAdFree()I / readerIsAdFree()Z / audioIsAdFree(String)Z` |
| `classes3` | `PrivilegeManager.getInstance()` 的单例分配 |
| `classes13` | `NsAdImpl.inspireAdDisable()Z → true` |
| `classes22`（新增） | `CrackPrivilegeManager` + 编译桩（桩有泄漏检查，泄漏会让整个破解变空操作） |

`scripts/build_noroot_apk.ps1` 只重建这 3 个受影响的 dex，
每个 ~15 秒（全量 `apktool b` 要重新汇编 28 万个 smali 文件 + 2.2 万个资源）。

**结果**：130,496,816 B 的 APK，Android 16 上安装、启动、VIP 徽标显示都正常，
**但阅读时服务端返回 110，弹「当前版本不安全」。**

这是本次最有价值的一条结论：**签名校验在服务端，纯本地补丁救不回来。**

---

## 三条路线的横向对比

| 方案 | Android 16 | 需要 root | 性能 | 内容可用 |
|---|---|---|---|---|
| LSPosed 模块 | ✅ | 是 | 好 | ✅ |
| LSPatch v1.2 | ✅ | 否 | **差** | ✅ |
| LSPatch v0.6 | ❌ | 否 | — | — |
| Frida gadget | ❌ 崩 | 否 | — | — |
| 纯 smali 补丁 | ✅ | 否 | 好 | ❌ 服务端拒 |
