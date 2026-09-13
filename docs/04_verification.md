# 验证

## 判据

「模块加载了」不等于「模块生效了」，两者之间隔着一次运行时链接。
本项目用三层证据区分：

| 层 | 证据 | 说明 |
|---|---|---|
| 1 安装 | `SELFTEST hooks_installed=57 hooks_missed=0` | 所有 hook 都装上了 |
| 2 触发 | `PROBE* …` 计数 | hook **真的被应用代码调到了** |
| 3 后果 | 应用侧日志 + 界面 | 目标行为确实变了 |

第 2 层是第一轮缺失的：当时 55 个 hook 全部「安装成功」，
然后全部在运行时抛 `NoSuchMethodError`，日志里只有一个 `hooks_installed=55`
看起来完全正常。

---

## 实测日志（MuMu / Android 12）

```
I FanQieCrack: ================ FanQieNovelCrack attached ================
I FanQieCrack: target=com.dragon.read  module-build=7.3.5.32 (73532)
I FanQieCrack: SELFTEST hooks_installed=57 hooks_missed=0 took_us=77698
I FanQieCrack: SELFTEST at-attach probe_fires=0 hooks_installed=57 hooks_missed=0

I FanQieCrack: PROBE PrivilegeManager.isVip  -> true
I FanQieCrack: PROBE getPrivilege  id=7025948416286921516 -> synthesised forever-model
I FanQieCrack: PROBE* getPrivilege  id=6836977122288866051 (AutoPage/自动阅读) -> synthesised forever-model
I FanQieCrack: PROBE  PrivilegeInfoModel.isForever  -> true
I FanQieCrack: PROBE* zh6.e.b  自动阅读权益可用 -> true
```

`PROBE*` 是 `Probe.important()`，不受采样上限截断——
这几条正是作业要求本身，不能被 40 条采样淹掉。

## 应用侧反证

```
$ adb logcat -d | Select-String "自动阅读权益已到期"
（0 次）

$ adb logcat -d | Select-String "当前无自动阅读权益"
（0 次）
```

这两句是两条广告路径各自的日志出口。
**它们的出现次数是 0，说明广告分支根本没进去**——
比正面日志更有说服力。

自动阅读心跳仍在跑（`getPrivilege(7025948416286921516)` 每秒一次，采样到 28 次）。

## 界面证据

| 观察 | 结果 |
|---|---|
| 阅读器页码 | `1/16795` → `2/16796` → `3/9280` 连续自翻 |
| 自动阅读面板 | 只有「左右翻页 / 上下滚屏 / 速度 / 退出」，**没有任何配额或看广告提示** |
| 真机（Android 16） | 账号旁显示 VIP 徽标，布局正常 |
| 广告 | 阅读过程中无插屏、无激励视频入口 |

## 复现步骤

```powershell
cd <repo>
.\module\build.ps1
.\scripts\deploy_lsposed.ps1 -SkipLaunch

adb -s 127.0.0.1:16384 logcat -c
adb -s 127.0.0.1:16384 shell "monkey -p com.dragon.read -c android.intent.category.LAUNCHER 1"
# 进阅读器 → 点中间 → 设置 → 开启自动阅读
adb -s 127.0.0.1:16384 logcat -d -s FanQieCrack:* | Select-String "SELFTEST|PROBE\*"
adb -s 127.0.0.1:16384 logcat -d | Select-String "自动阅读权益已到期"   # 期望 0 次
```

## 日志归档

```
evidence/lsposed_fanqiecrack.log             第一轮（55 hook，仅会员路径）
evidence/lsposed_fanqiecrack_after_fix.log   第二轮（57 hook，含自动阅读门）
evidence/magisk_install.log                  Magisk System Mode 安装日志
```
