# 发版流程

## 一句话

```bash
python tools/release.py --bump
```

改版本号、构建签名 APK、校验签名、打标签、发 Release，一次做完。

## 前置条件

- JDK 17、Android SDK 35、Gradle（脚本内已按本机路径写死环境变量）
- 签名凭据存在：`D:\dev-tools\secrets\safestream\credentials.properties`
- 已配置 `gh` 登录且对 `hea784/SafeStream` 有写权限

脚本启动时会检查凭据是否存在、**是否误放在仓库目录内**（放进去会被提交），
不满足直接中止。

## 首次配置签名（换机器时才需要）

```bash
keytool -genkeypair -v -keystore release.jks -alias safestream \
  -keyalg RSA -keysize 2048 -validity 10000 -storetype JKS
```

生成 `credentials.properties`（**用正斜杠写路径**，Java properties 会把反斜杠
当转义符，`\d` `\r` `\n` 都会被吃掉，路径会变成 `D:dev-tools...` 这种残缺形式）：

```properties
storeFile=D:/dev-tools/secrets/safestream/release.jks
storePassword=***
keyAlias=safestream
keyPassword=***
```

凭据路径可用环境变量 `SAFESTREAM_SIGNING_PROPS` 覆盖。

## 密钥库备份（重要）

**签名密钥丢了就没法更新已安装的用户**——只能让他们卸载重装，数据全丢。
这不是能补救的事，所以密钥必须有多份备份。

当前备份点（哈希一致才算数）：

| 位置 | 说明 |
| --- | --- |
| `D:\dev-tools\secrets\safestream\` | 主副本，日常使用 |
| `F:\backups\safestream-signing\` | 备份 1 |
| `G:\backups\safestream-signing\` | 备份 2 |

换机器或换密钥时，用下面命令核对三份是否一致：

```bash
Get-FileHash D:\dev-tools\secrets\safestream\release.jks -Algorithm SHA256
Get-FileHash F:\backups\safestream-signing\release.jks -Algorithm SHA256
```

**已知弱点**：密码明文存放在密钥旁边的 properties 文件里。三份备份都同时含
密钥和密码，所以单盘丢失能防住，整套设备被盗防不住。真要提高强度得把密码移到
密码管理器，但那样每次构建都要手工输入——按目前的威胁模型不划算，先记着。

## 为什么二进制不进 git

APK 有 2MB+，每改一行代码都会产生一个无意义的二进制 diff，仓库体积迅速膨胀。
GitHub Release 附件才是放构建产物的地方。

## 常见问题

**`APK 未通过签名校验`** —— 没找到凭据或密钥对不上。脚本会在上传前拦住，
不会出现"发了但装不上"的情况。

**`github.com` 连不上** —— 本机到 `github.com` 时通时不通（本仓库就有此问题）。
`gh release create` 走 `api.github.com`，通常不受影响；`git push` 可能失败，
失败时用 `python tools/push_via_api.py` 走 API 推送。

**本地与远端历史分叉** —— API 推送会重写 author/committer，导致 SHA 不同。
用 `git fetch` 后 `git reset --soft origin/main` 再提交，避免强推。
