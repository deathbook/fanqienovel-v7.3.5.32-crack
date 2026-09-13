# 产物清单

## Release 资产

| 文件 | 大小 | SHA256 | 说明 |
|---|---|---|---|
| `FanQieNovelCrack-lsposed-v1.0.apk` | 20,947 B | `1CE36781F9850D88EBEDB04519FA31EB291BDF785A2F42E84384A286B260D6C8` | LSPosed 模块。官方包一个字节都不动，装到已 root + LSPosed 的设备即可 |

## 本地构建产物

| 文件 | 大小 | 说明 |
|---|---|---|
| `dist/FanQieNovelCrack-noroot-v1.0.apk` | 130,496,816 B | 纯 smali 补丁版无 root 包。能装能跑，但**服务端签名校验会返回错误码 110**，见 README §六 |
| `dist/lspatch12/original-487-lspatched.apk` | 134,562,562 B | LSPatch v1.2 内嵌模块版。功能走通但**明显卡顿**（`-l 3` 的 native 插桩代价） |

后两者体积大且都有已知问题，故不进 Release，只在此登记。

## 样本

| 项 | 值 |
|---|---|
| 文件名 | `novelapp_43536163a_v1327_73532_73532_2e02_1787908885.apk` |
| 大小 | 129,186,145 B |
| SHA256 | `7DDF849219279D6563426E56BA8D79C095E33EF46B7828C55771379A2C49441E` |
| 包名 / 版本 | `com.dragon.read` / 7.3.5.32 (73532) |
| ABI | **仅 `armeabi-v7a`** |
| DEX | 21 个，280,957 个 smali 类 |

## 模块内部构成

| 文件 | 大小 |
|---|---|
| `classes.dex` | 19,724 B |
| `assets/xposed_init` | 45 B |
| `AndroidManifest.xml` | 2,144 B |
| `resources.arsc` | 884 B |
| 签名 | v1 + v2 + v3（自签，`CN=FanQieNovelCrack`） |

## 验证哈希

```bash
sha256sum FanQieNovelCrack-lsposed-v1.0.apk
# 1CE36781F9850D88EBEDB04519FA31EB291BDF785A2F42E84384A286B260D6C8
```
