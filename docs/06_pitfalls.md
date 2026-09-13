# 踩过的坑

按「症状 → 根因 → 修法」记。前六个是**真缺陷**，后四个是工具链/环境问题。

---

## 1. 55 个 hook 全部装上，然后全部失效

**症状**

```
SELFTEST hooks_installed=55 hooks_missed=0
hook miss: …PrivilegeManager.hasPrivilege --
  java.lang.NoSuchMethodError: No static method
  findAndHookMethod(Ljava/lang/Class;Ljava/lang/String;[Ljava/lang/Object;)V
```

模块编译、打包、安装、**加载**全部成功，日志里 `hooks_installed=55` 看起来完全正常。

**根因**：编译桩里 `findAndHookMethod` 的返回值写成了 `void`，
真值是 `XC_MethodHook$Unhook`。描述符不匹配 → `NoSuchMethodError`。

javac 只校验桩，而**桩按定义就是假的**。

**修法**：从设备上真实的框架 dex（`/data/adb/modules/zygisk_lsposed/framework/lspd.dex`）
逐条读出签名，并加 `scripts/verify_xposed_api.py` 做**构建期链接校验**——
模块 dex 里每一条 `de.robv.android.xposed.*` 引用都要在真框架里存在，否则拒绝出包。

> 这个脚本第一次跑就抓出了**两个**错（另一个是 `hookAllMethods` 的返回类型）。
> 现在它是我对这个项目里最有价值的一行防线。

---

## 2. 自动阅读到期仍弹广告

**症状**：会员显示了，自动阅读跑一会儿后仍弹「看广告继续自动阅读」。

**根因**：`自动阅读权益已到期` 有**两条**广告路径，第一轮只处理了权益合成，
没处理广告分支：

```java
expired = true ^ zh6.e.b();                 // 权威门
if (NsAdApi.IMPL.inspireAdDisable()) expired = false;   // ★ 漏了
if (expired) zh6.e.c(2, ...);               // 播激励视频
```

第二条路径 `zh6.m.subscribe` 查的是**同一个** `inspireAdDisable()`。

**关键教训**：**「权益被合成」和「广告分支被关掉」在真机上不等价。**
MuMu 上验证过前者就以为完成了。

**修法**：新增 `HookAutoRead`，同时关 `zh6.e.b()`（权威门）与
`NsAdImpl.inspireAdDisable()`（App 自带的 AB 开关）。

**验证**：`PROBE* zh6.e.b 自动阅读权益可用 -> true`，
应用侧 `自动阅读权益已到期` / `当前无自动阅读权益` 各 **0 次**。

---

## 3. 时间字段单位错了（毫秒 vs 秒）

**症状**：会员页日期文字异常 / 布局被撑变形。

**根因**：`PrivilegeInfoModel.expire_time` 是**秒**，我写的是毫秒级天文数字：

```java
public static final long FOREVER_EXPIRE_MS = 99_999_999_999_999L;   // 当秒读 = 公元 317 万年
```

任何格式化该字段的控件都会渲染出 12+ 位年份的字符串。

**判据**（`a54.g.getCommentForbiddenLeftDays()`）：

```java
long expireTime = (commentForbiddenPrivilege.getExpireTime() * 1000)
                  - System.currentTimeMillis();
```

自己 `* 1000`，说明源值是秒。

**修法**：改用番茄自己的永久哨兵 `113_143_651_200` 秒 = **5555-05-20**，
即它自家「永久会员」界面上显示的那个日期。宽度与官方一致。

---

## 4. `Long.MAX_VALUE/4 × 1000` 溢出成负数

**根因**：

```java
// PrivilegeInfoModel.leftSecondsAfterInit
long left = ((leftTime * 1000) - (SystemClock.elapsedRealtime() - initTs)) / 1000;
if (left <= 0) return 0L;
```

`Long.MAX_VALUE/4 = 2305843009213693951`，`×1000` 超出 `long` → **溢出为负**
→ `left <= 0` → **返回 0** → 权益被判「已过期」，与意图完全相反。

**修法**：

```java
public static final long FOREVER_SECONDS = Long.MAX_VALUE / 1000L;   // ≈ 2.92 亿年
```

这是 `×1000` 之后不溢出的最大值。

> 这两个坑是同一次修复里发现的，方向相反：一个太大（按秒读离谱），
> 一个太大（按毫秒乘溢出）。**凡是「永久」哨兵值，都要先确认单位与下游算术。**

---

## 5. 陈旧产物：改了 smali 没重编 dex

**症状**：修了注入位置，重打包，安装，报错和上次**一模一样**。

**根因**：打包脚本用的是上次生成的 `classes21_patched.dex`，
smali 改了但没重跑汇编 → 产出字节相同的 APK。

**修法**：`scripts/build_noroot_apk.ps1` 比较 smali 与 dex 的时间戳，落后就自动重编。

---

## 6. `getFilesDir()` 在 `attachBaseContext` 里 NPE

见 `05_noroot_lspatch.md` 坑 A。
`p0`（Application 自身）的 `mBase` 在 super 调用前是 null，要用传入的 `p1`。
报错文本完全没提这件事。

---

## 7. PowerShell：单元素数组 splat 会切坏 `C:\` 路径

**症状**：`d8` 报 `Error in program input 'C'` 和 `NoSuchFileException: C`。

**根因**：PowerShell 5.1 对**单元素**数组 splat 一个含冒号的 Windows 路径会切成两段。
模块构建传 15 个文件没事，Bootstrap 只传 1 个就炸。

**修法**：`Start-Process -ArgumentList`（它会正确加引号），并把这个理由写进注释。
另外 `d8`（build-tools 33）不接受目录作为 program input，所以不能退化成单个参数。

---

## 8. PowerShell：`String.Split(string)` 按字符切

**症状**：一个「应该只出现 1 次」的检查报「出现多次」。

**根因**：`$t.Split($pattern)` 绑定到 `Split(char[])`，把字符串**强制转成 char 数组**，
于是按模式里每一个字符切分。

**修法**：`[regex]::Matches($t, [regex]::Escape($pattern)).Count`。

---

## 9. PowerShell：`cmd | Select-Object -First N` 会杀掉 native 进程

**症状**：`apksigner verify` 明明成功，脚本却报 verify failed。

**根因**：`Select-Object -First` 提前结束管道，
向上游发 `StopUpstreamCommandsException`，把 native 进程杀掉，
`$LASTEXITCODE` 变成非零。

**修法**：先完整捕获到变量再切片。

---

## 10. PowerShell：函数名遮蔽同名外部命令

**症状**：部署脚本「调用深度溢出」——函数递归调用自己。

**根因**：定义了 `function Adb(...)`，而 PowerShell 里**函数优先级高于外部命令**，
函数体内的 `& adb` 解析回了函数本身。

**修法**：函数改名 `Invoke-Adb`；顺带 `$Args` 是自动变量，也不能当参数名。

---

## 11. 工具链：JDK 24 跑不了 build-tools 的 `d8.bat`

**症状**：`Could not create the Java Virtual Machine` /
`-Djava.ext.dirs=… is not supported`。

**根因**：`d8.bat` 传 `-Djava.ext.dirs`，该选项在 JDK 9 移除、JDK 24 直接拒绝。

**修法**：直接调主类 `java -cp <bt>/lib/d8.jar com.android.tools.r8.D8 …`，
不依赖 JDK 版本。（`apksigner.bat` 用的是 `-jar`，不受影响。）

---

## 12. 环境事实：`abilist` 判断不了 32 位能力

真机 `ro.product.cpu.abilist` 只报 `arm64-v8a`、`abilist32` 为空，
按属性该判「不能跑 32 位样本」，实测**能跑**：

```
D nativeloader: Load /data/app/…/lib/arm/libnslinker.so … : ok
```

**不要用属性判断，要实测。**
