# FanQieNovel v7.3.5.32 破解 —— 番茄免费小说（deathbook/Crack Lesson2）

| 项 | 内容 |
|---|---|
| 学员 | deathbook |
| 样本 | `novelapp_43536163a_v1327_73532_73532_2e02_1787908885.apk`（`com.dragon.read` v7.3.5.32 / versionCode 73532，129,186,145 B，SHA256 `7DDF8492…2C49441E`） |
| 目标 | 破解会员 · 去除广告 · 无限时长自动阅读 |
| 交付 | ① **LSPosed 模块**（20.9 KB，官方包不动）② **无 root 直装包**（LSPatch 内嵌，见 §六） |
| 真机 | Xiaomi 23116PN5BC / Android 16 / arm64（跑 32 位样本） |
| 模拟器 | MuMu Player 12 / Android 12 / x86_64 + libhoudini ARM 翻译 |

---

## 一、结果

| 项 | 结果 |
|---|---|
| hook 安装 | `SELFTEST hooks_installed=57 hooks_missed=0` |
| 会员 | `PrivilegeManager.isVip()` → true；三个 VIP 接缝（`NsVipImpl` / `NsCommonDependImpl` / `NsUserInfoDependImpl`）同步覆盖 |
| 广告 | 权益层 `hasNoAd*` 全开 + `NsAdDependImpl` 三个本地判定 + `NsAdImpl.inspireAdDisable()` |
| 自动阅读 | `getPrivilege("6836977122288866051")` 合成永久模型；`zh6.e.b()` 与 `isReaderAdFree()` 双保险关闭激励视频入口 |
| 实测日志 | `PROBE* zh6.e.b 自动阅读权益可用 -> true`；应用侧 `自动阅读权益已到期` / `当前无自动阅读权益` 各 **0 次** |
| 界面实测 | 自动阅读页码连续自翻（1/16795 → 2/16796 → 3/9280），无配额提示 |

---

## 二、保护分析

| 层 | 组件 | 说明 |
|---|---|---|
| 混淆 | R8 full mode | 21 个 dex / 280,957 个 smali 类；顶层包名被打散成 `a`…`z70`，资源名同样混淆 |
| 方法抽取 | `lib/armeabi-v7a/libdragoncore.so` | **263 KB**，用 `RegisterNatives` 注册 `PrivilegeManager` 的 13 个 native 方法 |
| 签名校验 | 服务端 | 改包后内容接口返回 **错误码 110**，客户端弹出「当前版本不安全，请到正规应用市场下载」 |
| 指纹 | `libmetasec_ml.so` / Tinker | 采集「是否装了 Xposed」等设备属性上报 |

**ABI 是最关键的约束**：样本只含 `armeabi-v7a`（116 个 .so，**无 arm64**）。
这一条否掉了 native hook 与 arm64 gadget，也让 Java 层 hook 成为唯一在两种载体上都成立的路子。

---

## 三、四个关键结论

### 1. 权益是一个枢纽，不是一个开关

`PrivilegeManager` 用 19 位雪花 ID 做 key 管住 18 项权益，
全部判定都收敛到两个 native 方法：

```
native boolean isVip()
native boolean hasPrivilege(String)
```

| ID | 含义 |
|---|---|
| `6825868665112494095` | Vip |
| `6703327401314620167` | NoAd |
| `7077535443348116268` | NoAdAllScene |
| `7313754740460884790` | ShortSeriesNoAd |
| **`6836977122288866051`** | **AutoPage（自动阅读）** |
| `7210376203117531962` | ReadPaidBook / OfflineReading / TTS ×3 / … （全表见 `module/src/.../Const.java`） |

**反破解设计正在这里**：改 `hasAutoPagePrivilege()` 的 smali 毫无作用，
因为答案来自 C++。LSPosed 在 Java 边界替换 ArtMethod 入口，
对 native 方法同样有效，且与 ABI 无关、不受 .so 重编译影响。

### 2. 「无限时长自动阅读」的正确表达式是 `isForever()`

自动阅读控制器 `AdAutoReadPreAccumulateControllerImpl.f()`：

```java
PrivilegeInfoModel p = NsVipApi.IMPL.privilegeManager().getPrivilege("6836977122288866051");
if (p == null)              remain = 0;
else if (p.isForever())     remain = Long.MAX_VALUE;
else                        remain = p.leftSecondsAfterInit(null);
```

所以「无限」= 让 `getPrivilege` 返回非空 + `isForever()` 为真。
但**光有这一条不够**——见结论 3。

### 3. 「权益已到期」有两条广告路径，且共用同一个 kill switch

从字符串 `自动阅读权益已到期` 反查，全仓库只有一个发射处：

```java
// TemporaryReaderLifecycleListener$b.onReceive  (action_auto_read_changed)
expired = true ^ zh6.e.b();                 // 权威门
if (q.b.isEnabled())                    expired = false;
if (NsAdDepend.IMPL.readerIsAdFree())   expired = false;
if (NsAdApi.IMPL.inspireAdDisable())    expired = false;   // ★
if (NsMineDepend.IMPL.isGoogleMarket()) expired = false;
if (expired) zh6.e.c(2, ...);               // 播放激励视频
```

第二条路径 `zh6.m.subscribe`（`当前无自动阅读权益`）查的是**同一个** `inspireAdDisable()`。

`inspireAdDisable()` 读服务端 AB 配置 `AutoReadingShowAd.inspireAdDisable`，
语义就是「自动阅读不出激励广告」——比 `isGoogleMarket()` 安全（后者会改变 App 认为自己具备的功能集）。

**第一轮只验证了「权益被合成」，没验证「广告分支被关掉」，这两件事在真机上不等价。**

### 4. 时间字段是**秒**，且 `Long.MAX_VALUE/4 × 1000` 会溢出

单位证据在 `a54.g.getCommentForbiddenLeftDays()`：

```java
long expireTime = (commentForbiddenPrivilege.getExpireTime() * 1000)
                  - System.currentTimeMillis();      // ← 自己 *1000，说明源值是秒
```

第一轮踩了两个坑，**都是真缺陷**：

| 写法 | 后果 |
|---|---|
| `FOREVER_EXPIRE = 99_999_999_999_999`（当秒） | 公元 317 万年 → 任何格式化该字段的控件都会渲染超长日期串 |
| `FOREVER_SECONDS = Long.MAX_VALUE/4` | `leftSecondsAfterInit` 内部 `×1000` **溢出成负数** → 被判「已过期」，与意图完全相反 |

正确值：

```java
FOREVER_SECONDS        = Long.MAX_VALUE / 1000L;   // ≈ 2.92 亿年，×1000 不溢出
FOREVER_EXPIRE_SECONDS = 113_143_651_200L;         // = 5555-05-20，番茄自己的永久哨兵
```

`113_143_651_200` 秒正是番茄「永久会员」显示的 `5555-05-20`——
用官方哨兵值，日期宽度与官方完全一致。

---

## 四、仓库结构

```
docs/      01_protection_analysis.md   样本、ABI、native 保护、签名校验
           02_hook_targets.md          57 个 hook 的清单与依据
           03_lsposed_setup.md         MuMu + Magisk Delta + Zygisk + LSPosed 搭建
           04_verification.md          实测日志与结论
           05_noroot_lspatch.md        无 root 路线的三次尝试与结论
           06_pitfalls.md              踩过的坑（含两处真缺陷）
evidence/  lsposed_attach.log · lsposed_autoread.log · magisk_install.log
module/    src/                       模块源码（10 个类）
           stubs/                     编译桩（不进 dex，有泄漏检查）
           noroot/                    无 root 用：CrackPrivilegeManager + 桩
           build.ps1                  无 gradle 构建（aapt2 + javac + d8 + apksigner）
frida/     fanqie_crack.js            Frida 等价实现
scripts/   dex_probe.py               ★ 直接解析 DEX 的精确定位工具
           bso_grep.py                .so 字节级检索
           verify_xposed_api.py       ★ Xposed API 链接期校验
           deploy_lsposed.ps1         部署 + 自动重指向 LSPosed 数据库
           build_noroot_apk.ps1       纯 smali 补丁版无 root 包
```

**二进制产物在 Releases**：`FanQieNovelCrack-lsposed-v1.0.apk`（20,947 B）。

---

## 五、构建与部署

无需 gradle：模块没有 Activity、没有布局、没有运行时依赖，
四个命令几秒钟出包，完全可复现。

```powershell
# 一次性：Android 构建链
sdkmanager "platforms;android-33" "build-tools;33.0.2"

.\module\build.ps1                 # 产出 dist/FanQieNovelCrack-lsposed-v1.0.apk
.\scripts\deploy_lsposed.ps1       # 装模块 + 重指向 LSPosed 库 + 重启 + 抓日志
```

`build.ps1` 里有一道 **链接期校验**（`scripts/verify_xposed_api.py`）：
把模块 dex 里所有 `de.robv.android.xposed.*` 引用逐条比对设备上真实的框架 dex 描述符。

这道检查不是洁癖。第一版模块编译、打包、安装、**加载全部成功**，
然后 57 个 hook 全部以 `NoSuchMethodError: findAndHookMethod(…)V` 失败 ——
因为编译桩把返回值写成了 `void`，真值是 `XC_MethodHook$Unhook`。
javac 只看桩，而桩按定义就是假的；**只有比对真框架才能发现**。

### LSPosed 部署的两个坑

1. **LSPosed 存的是模块 APK 的绝对路径**。每次重装模块路径都会变，
   不更新数据库就永远加载旧包。`deploy_lsposed.ps1` 自动做这件事
   （等价于在管理器里点开关，管理器列表刷不出来时也能用）。
2. **模拟器需要「可写系统盘」**。MuMu 官方文档路径：
   设置 → 磁盘 → 可写系统盘；其他 → 开启 Root；然后 Kitsune Mask
   **直接安装（直接修改 /system）** → Zygisk → LSPosed v1.8.6。

---

## 六、无 root 路线：三次尝试

| 方案 | 结果 |
|---|---|
| **Frida gadget 重打包** | ❌ gadget 一加载即 `SIGSEGV (SEGV_MAPERR, fault addr 0x24)`。ABI（ELF32/ARM）、config 落盘、脚本路径均已排除，剩余怀疑是 frida-gadget 17.x 与 Android 16 的兼容性 |
| **LSPatch v0.6（官方）** | ❌ Android 16 不可用：`Failed to init lsplant` + `NoSuchFieldError: ActivityThread$AppBindData#compatInfo`（该字段在 Android 16 被移除） |
| **LSPatch v1.2（JingMatrix 维护分支）** | ✅ **跑通**：`Signature bypass level: 3`、模块加载、`hooks_installed=57 hooks_missed=0` |
| 纯 smali 补丁（自建） | ⚠️ 能装能跑，但**服务端返回错误码 110** —— 签名校验在服务端，本地补丁无法绕过 |

### 关于服务端签名校验

纯 smali 补丁版会被拦，链路如下（`ChapterOriginalContentHelper`）：

```smali
invoke-static {p1}, L.../m2;->a(Ljava/lang/Throwable;)I   # 取错误码
const/16 v7, 0x6e                                          # 0x6e = 110
if-eq v2, v7, :cond_1
:cond_1
iget-object v7, ...->a:Ljava/util/HashSet;                 # 把 chapterId 加进本地黑名单
invoke-virtual {v7, v4}, Ljava/util/HashSet;->add(Ljava/lang/Object;)Z
```

即**服务端拒绝为改包客户端下发内容**，客户端只是把结果缓存后复用同一提示。
因此任何纯本地 patch 都不能让内容回来——必须让服务端看到**原始签名**，
这正是 LSPatch `-l 1/2/3` 在做的事。

**LSPatch v1.2 是唯一走通的无 root 路线。**

### 已知边界

* LSPatch 版**明显卡顿**。`-l 3` 会改写 native 层来拦截原始 syscall 读 APK，
  代价是每次读取都要过一遍插桩；对番茄这种每秒都在读资源的 App 难以接受。
  要流畅体验请用 **LSPosed 模块**（APK 不动、无签名绕过开销）。
* 重打包包与官方包**签名不同**，必须先卸载官方版。
* 样本只有 `armeabi-v7a`。arm64-only 设备（如部分新旗舰）能否运行取决于厂商是否保留 AArch32——
  `ro.product.cpu.abilist` 在这台机器上只有 `arm64-v8a`，但实测**能跑**，
  该属性对 32 位能力的判断并不可靠。

---

## 七、授权

仅用于 `deathbook/Crack` 课程作业与授权范围内的安全研究。
