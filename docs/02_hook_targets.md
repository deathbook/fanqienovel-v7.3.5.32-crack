# hook 清单与依据

全部 57 个 hook 都在 Java 边界。**没有一个 native hook**——样本只有 32 位 ARM 库，
而 Java 层替换 ArtMethod 入口对 native 方法同样有效，且与 ABI 无关。

安装顺序有讲究：`privileges` → `vip` → `auto-read` → `ads` → `anti-detect`。
`auto-read` 的门读的就是 privilege map，必须排在它后面。

---

## 1. HookPrivileges —— 权益主枢纽

| 目标 | 处理 | 依据 |
|---|---|---|
| `PrivilegeManager.hasPrivilege(String)` | 白名单内返回 true，其余走原实现 | **native**，所有 `has*Privilege()` 的汇聚点 |
| `PrivilegeManager.getPrivilege(String)` | 为空时合成永久模型 | 纯 Java `HashMap.get`；自动阅读控制器直接读它，**完全不查 hasPrivilege** |
| `PrivilegeInfoModel.isForever()` | → true | 「无限时长」的开关表达式 |
| `PrivilegeInfoModel.leftSecondsAfterInit(Long)` | → `Long.MAX_VALUE/1000` | 控制器算预算用 |
| `PrivilegeInfoModel.getExpireTime()` | → `113143651200`（秒）= 5555-05-20 | 见 `06_pitfalls.md` |
| `PrivilegeInfoModel.available()` / `getIsForever()` | true / 1 | 兜底 |
| 18 个具名 `has*Privilege()` | 详见源码 | 逐个覆盖，防止某个方法在查 map 之前就短路成字面量 false |

**两个刻意的不做**：

* `CommentForbidden`（`6885168538881889039`）**不在白名单**。它是**负向**权益，
  一刀切 `hasPrivilege → true` 会把用户的评论区拿走——这类「破解完反而少功能」的 bug 最典型。
  同时 `checkCommentForbidden()` 强制 false。
* 白名单之外的 ID **走原实现**，服务端驱动的新权益照常工作。

## 2. HookVip —— PrivilegeManager 之外的三条 VIP 接缝

156 个 `isVip()` 调用点走的是三扇不同的门，只堵 `PrivilegeManager` 那一扇，
会出现「阅读器当你是 VIP、我的页还说未开通」的分裂状态。

| 目标 | 说明 |
|---|---|
| `NsVipImpl.isVip(VipCommonSubType)` | SDK 侧接缝，带子类型（SVIP / 免广告 VIP / 出版 VIP） |
| `NsCommonDependImpl.isVip()` + `(VipCommonSubType)` | app-service 侧 |
| `NsUserInfoDependImpl.isVip()` | H5 `/user/info` 桥 |

子类型谓词对所有 `VipCommonSubType` 都返回 true，而不是只答普通 VIP：
App 会分别问「是不是免广告 VIP」「是不是 SVIP」来画徽标和跳短剧贴片。

## 3. HookAds —— 本地算出来的广告判定

`NsAdDependImpl` 是 ~120 个方法的 `NsAdApi` 实现，阅读器/听书/短剧/开屏都通过
`NsAdApi.IMPL` 访问它。其中三个谓词**根本不查权益表**，是从当前书的广告配置算的，
`PrivilegeManager` 的子类/ hook 够不着：

```
isReaderAdFree()I      → 1    注意返回 int 不是 boolean：readerIsAdFree() 就是 isReaderAdFree() != 0
readerIsAdFree()Z      → true
audioIsAdFree(String)Z → true
```

其余：`readerHasLeftAdForFreeChapter` / `enableReaderNaturalFlowBanner` /
`isLocalBookShowChapterFrontAd` / `isLocalBookShowChapterMiddleAd` → false，
`getMiddleAdCount` → 0，`keepInspireEntrance(String)` → 空。

外加两个 native 的书级开关，**两者签名不同**：

```
BookInfo.isAdFree()Z          （无参）
SaaSBookInfo.isAdFree(Z)Z     （带调用方自己的 adFree 提示）
```

## 4. HookAutoRead —— 自动阅读的两道门

第一轮漏掉的部分。详见 `05_noroot_lspatch.md` 与 README §三.3。

| 目标 | 值 | 依据 |
|---|---|---|
| `zh6.e.b()Z`（静态） | true | 唯一权威门，两条广告路径都读它 |
| `NsAdImpl.inspireAdDisable()Z` | true | App **自带**的 AB 开关，两条路径都会先查 |

`zh6.e.b()` 反编译：

```java
public static boolean b() {
    PrivilegeInfoModel p = NsVipApi.IMPL.privilegeManager().getPrivilege("6836977122288866051");
    if (p == null) return false;
    if (!p.isForever() && p.leftSecondsAfterInit(null) <= 0) return false;
    return true;
}
```

## 5. HookAntiDetect —— 保持设备指纹干净

```
DeviceUtils.isInstallXposed()Z        → false   （两个同名的不同包）
Utils.isXposedExists(Throwable)Z      → false   （Tinker 崩溃处理器）
```

两者都**不是 kill switch**——反编译调用方可见结果只进上报载荷。
正因为如此才值得答「否」：否则每次启动都在上报「本机有 Xposed」，
是给改包客户端打标最便宜的方式。

---

## 为什么 57 个而不是 55 个

`hooks_installed` 从 55 涨到 57，就是加上了 `zh6.e.b()` 与 `inspireAdDisable()`。
**这个数字是确认「跑对了构建」最快的标志。**

## 失败模式：装了 hook ≠ hook 生效

第一版模块 55 个 hook 全部「安装成功」，然后全部在运行时抛
`NoSuchMethodError`。原因是编译桩把 `findAndHookMethod` 的返回值写成了 `void`，
真值是 `XC_MethodHook$Unhook`。

javac 只校验桩，而桩按定义就是假的。为此加了
`scripts/verify_xposed_api.py`：构建时把模块 dex 里每一条
`de.robv.android.xposed.*` 引用，与设备上真实的框架 dex 描述符逐条比对，
对不上就**拒绝出包**。

同理，`Probe` 记录每个 hook 实际被调用的次数——
「hook 装上了」和「hook 被调到」是两件事，只有计数器能区分。
`Probe.important()` 用于必须留痕的关键判定（自动阅读门），不受采样上限影响。
