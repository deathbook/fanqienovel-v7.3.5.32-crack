# 样本、保护与载体约束

## 样本

```
novelapp_43536163a_v1327_73532_73532_2e02_1787908885.apk
129,186,145 B
SHA256 7DDF849219279D6563426E56BA8D79C095E33EF46B7828C55771379A2C49441E
com.dragon.read   7.3.5.32 (versionCode 73532)   minSdk 21 / targetSdk 35
```

课程原文说这是「仿照番茄小说」写的样本。实测下来它就是**真实番茄 APK 重打包**：
21 个 dex、28 万个类、21898 个资源、完整的 `com.dragon.read.*` 命名空间，
以及字节跳动全套 SDK（metasec、Lynx、Tinker、GeckoX、播放器……）。

## ABI：整条技术路线的约束条件

```
lib/armeabi-v7a/   116 个 .so
lib/arm64-v8a/     （不存在）
```

样本**只含 32 位 ARM 库**。这一条决定了后面所有取舍：

* native hook / arm64 gizmo 全部出局
* Frida gadget 必须用 `android-arm`（32 位），而它在 Android 16 上崩
* **Java 层 hook 成为唯一在「MuMu + ARM 翻译」与「真机」上都成立的路子**

顺带一个反直觉的实测结论：真机 Xiaomi 23116PN5BC 的
`ro.product.cpu.abilist` 只报 `arm64-v8a`（`abilist32` 为空），
按属性判断应当**无法**运行本样本——实际上它能跑：

```
D nativeloader: Load /data/app/…/lib/arm/libnslinker.so … : ok
```

**不要用 `abilist` 判断 32 位能力，要实测。**

## native 保护层：libdragoncore.so

`PrivilegeManager` 有 13 个 native 方法，全部由 **`lib/armeabi-v7a/libdragoncore.so`（263 KB）** 实现：

```
native boolean isVip()
native boolean hasPrivilege(String)
native boolean hasNoAdPrivilege()
native boolean hasNoAdFollAllScene()
native boolean hasNoAdForShortSeries()
native boolean hasNoAdReadConsumptionPrivilege()
native boolean isForeverNoAd()
native boolean isNoAd(String)
native int     isBookAdFree(String)
native boolean canShowVipRelational()
native boolean showPayVipEntranceInChapterEnd()
native String  getPrivilegeJson()
native void    updateVipInfo(VipInfoModel, boolean)
```

定位方式（`scripts/bso_grep.py`）——在这些 .so 里搜类名与权益 ID：

```
lib/armeabi-v7a/libdragoncore.so  263,780  'PrivilegeManager'@47561
                                            'hasNoAdPrivilege'@53817
                                            'getPrivilegeJson'@81423
                                            'com/dragon/read/component/biz/impl/privilege'@48395
```

导出表里**只有**这两个静态 JNI 名字：

```
Java_com_dragon_read_api_bookapi_BookInfo_isAdFree__
Java_com_dragon_read_reader_model_SaaSBookInfo_isAdFree__Z
```

其余 13 个走 `RegisterNatives`（符号表里看不到 `RegisterNatives` 本身，
因为它是通过 `JNIEnv` 函数表间接调用的；但方法名字符串 + `()Z` /
`(Ljava/lang/String;)Z` 这类签名字符串同处一个数据段，是典型的
`JNINativeMethod[]` 布局，`JNI_OnLoad` 存在）。

**这一点有硬性后果**：在 smali 里把这 13 个方法从 `native` 改成普通方法，
会让 `RegisterNatives` 注册失败 → `JNI_OnLoad` 返回 `JNI_ERR` →
`System.loadLibrary` 抛异常 → **整个 App 起不来**。

只能绕，不能拆。三种绕法：

| 路子 | 做法 | 代价 |
|---|---|---|
| LSPosed | 替换 ArtMethod 入口 | 需 root（本项目主交付） |
| 子类 | `PrivilegeManager` **不是 final**，可以继承并用 Java 方法覆盖 native 方法 | 需改 `getInstance()`（无 root 用） |
| native patch | 直接改 .so 机器码 | 破坏完整性校验，且要逆 arm32 |

## 其余保护

| 层 | 组件 | 观察 |
|---|---|---|
| 混淆 | R8 full mode | 顶层包名打散成 `a`…`z70`；资源名同样混淆（`res/d/b.json`）；字符串池 121 万条 |
| 自定义 linker | `libnslinker.so` | 自建 namespace 加载 native 库，日志有 `NSLinker: … android_link_namespace success` |
| 设备指纹 | `libmetasec_ml.so`、Tinker | `DeviceUtils.isInstallXposed()` 采「是否装了 Xposed」并上报 |
| 签名校验 | **服务端** | 见下 |

### 签名校验是服务端的

这一点花了最久才确认。现象是改包后阅读时弹
「当前版本不安全，请到正规应用市场下载」，而该提示的**字符串是本地资源** `R.string.cjw`：

```xml
<string name="cjw">当前版本不安全，请到正规应用市场下载</string>
```

追到引用点后真相是：

```java
// ChapterOriginalContentHelper.d(...)
if (this.f245079a.contains(str2)) {                       // 本地黑名单
    return Single.error(new ErrorCodeException(110, "当前版本不安全"));
}
```

而黑名单**只在一处被写入**（`ChapterOriginalContentHelper$…x0.invoke`）：

```smali
invoke-static {p1}, L.../m2;->a(Ljava/lang/Throwable;)I   # 从异常里取错误码
const/16 v7, 0x6e                                          # 0x6e = 110
if-eq v2, v7, :cond_1
:cond_1
iget-object v7, ...->a:Ljava/util/HashSet;
invoke-virtual {v7, v4}, Ljava/util/HashSet;->add(Ljava/lang/Object;)Z
```

即：**服务端对改包客户端返回 110，客户端把 chapterId 记下来，后续请求直接短路复用同一提示**。

结论：内容拿不到不是客户端在拦，是服务端不下发。
**任何纯本地 patch 都救不回来**——必须让服务端看到原始签名，
也就是必须做签名绕过（LSPatch 的 `-l 1/2/3`）。

## 时间字段单位

`PrivilegeInfoModel` 的 `leftTime` / `expireTime` 都是**秒**。

证据（`a54.g.getCommentForbiddenLeftDays()`）：

```java
long expireTime = (commentForbiddenPrivilege.getExpireTime() * 1000)
                  - System.currentTimeMillis();
int days = (int) Math.ceil(expireTime / 8.64E7f);
```

`leftSecondsAfterInit(Long)` 同理：

```java
long left = ((leftTime * 1000) - (SystemClock.elapsedRealtime() - initTs)) / 1000;
if (left <= 0) return 0L;
```

**所以 `FOREVER_SECONDS` 必须满足 `×1000` 不溢出**，
`FOREVER_EXPIRE` 必须是一个**当作秒**读也合理的值。
详见 `06_pitfalls.md`。
