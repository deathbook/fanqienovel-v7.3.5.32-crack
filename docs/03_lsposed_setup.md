# LSPosed 环境搭建（MuMu Player 12 / Android 12 / x86_64）

主交付路线。官方 APK 一个字节都不动，所以**不触发服务端签名校验**，
也没有重打包的性能损失。

## 目标环境

| 项 | 值 |
|---|---|
| 载体 | MuMu Player 12（Windows），Android 12 / SDK 32 / x86_64 |
| ARM 支持 | `libhoudini.so`（`RunningArchitecture=armeabi-v7a`，`NB_64BIT=false`） |
| Root | **Magisk Delta（Kitsune Mask）System Mode** |
| 框架 | Zygisk + LSPosed 1.8.6 (6712) |

样本只有 32 位 ARM 库，所以走的是 MuMu 的 ARM 翻译层。
**这不影响 Java 层 hook**——LSPosed 注入的是 ART 方法入口，与 native ABI 无关。

（真机 23116PN5BC / Android 16 / arm64 也实测能跑 32 位样本，见 `01_protection_analysis.md`。）

## 步骤

### 1. MuMu 设置

命令行等价物：

```powershell
$mm = "C:\Program Files\NetEase\MuMu\nx_main\MuMuManager.exe"
& $mm setting -v 0 -k system_disk_readonly -val false
& $mm setting -v 0 -k root_permission     -val true
& $mm control -v 0 shutdown ; Start-Sleep 12 ; & $mm control -v 0 launch
```

`system_disk_readonly=false` 是关键：Magisk System Mode 要写 `/system`。

### 2. Kitsune Mask 直装 Magisk

安包并启动，MuMu 会弹超级用户授权，选「**永久记住选择**」→「允许」。

随后 `Kitsune Mask → 安装`。**第一次打开时「直接安装」不会出现**，
强杀重启 App 后才会出现三个选项：

| 选项 | 用途 |
|---|---|
| 选择并修补一个文件 | 不选 |
| 直接安装（推荐） | 不选 |
| **直接安装（直接修改 /system）** | ← 模拟器要选这个 |

安装日志（`evidence/magisk_install.log`）：

```
- Device platform: x86_64
- Installing: v27.1-30370a9d-kitsune (27001)
Magisk Delta (System Mode)  by HuskyDG
- Remount system partition as read-write
- Cleaning up enviroment...
- Copy files to system partition
- Check if kernel supports dynamic SELinux Policy patch
- Add init boot script
- All done!
```

**注意日志里那句 `[*] Reflash your ROM if your ROM is unable to start` —— 这是真警告。**

重启后确认：

```
/system/bin/magisk      468440 B
/system/bin/su -> ./magisk
```

即 MuMu 自带的 `su` 被 Magisk 的替换了。

### 3. Zygisk

`Kitsune Mask → 设置 → Zygisk` 打开 → 重启。
主页应显示 `Zygisk：是`。

### 4. LSPosed

```powershell
adb push LSPosed-v1.8.6-6712-zygisk-release.zip /sdcard/Download/
```

`Kitsune Mask → 模块 → 从本地安装` 选该 zip。日志结尾：

```
- Welcome to LSPosed!
- Done
```

重启后 LSPosed 服务起来：

```
I/LSPosed  ZygiskCompanion: welcome to LSPosed!
I/LSPosed  ZygiskCompanion: version v1.8.6 (6712)
I/LSPosedService  manager is not installed
```

管理器再单独装：

```powershell
adb root && adb shell pm install -r /data/adb/lspd/manager.apk   # → org.lsposed.manager
```

管理器首页应显示 `已激活 / 1.8.6 (6712) - Zygisk / API 版本 93`。

### 5. 部署模块

```powershell
.\module\build.ps1
.\scripts\deploy_lsposed.ps1
```

---

## 两个必踩的坑

### 坑 1：LSPosed 存的是模块 APK 的**绝对路径**

```
/data/adb/lspd/config/modules_config.db
  modules(mid, module_pkg_name, apk_path, enabled)
  scope  (mid, app_pkg_name, user_id)
```

每次重装模块，`/data/app/<随机 tag>/` 都会变。不更新 `apk_path`，
LSPosed 会一直加载那个已经被删掉的旧包 —— 表现为「改了代码但行为没变」，
极难排查。

`scripts/deploy_lsposed.ps1` 自动做这件事：

```sql
UPDATE modules SET apk_path = '<pm path 查到的实际路径>', enabled = 1
 WHERE module_pkg_name = 'com.deathbook.fanqie.crack';
```

这等价于在管理器 UI 里点开关。顺带解决另一个问题：
本项目的模块在 LSPosed 1.8.6 管理器里**列表刷不出来**
（APK 的 `xposedmodule` meta-data 与 `assets/xposed_init` 都验证过是正确的，
甚至 LSPosed 守护进程已经给它加过 `QUERY_ALL_PACKAGES` 了），
直写数据库可以绕过这个 UI 问题。

### 坑 2：`adb root` 比 `su` 好用

Magisk 的 `su` 需要先在 App 里授权，且**重启后会重置为拒绝**：

```
W/Magisk  su: request rejected (2000)
```

而 MuMu 的 adbd 允许 `adb root` 直接拿 uid=0，不用碰 Magisk 的授权策略。
`deploy_lsposed.ps1` 走的就是这条路。

---

## 无 root 无需这些步骤

无 root 路线见 `05_noroot_lspatch.md`，不需要任何管理权限。
