"""SafeStream 发版一条龙：改版本号 -> 构建签名 APK -> 校验签名 -> 打标签 -> 发 Release。

手动发版最容易翻车的地方是"忘了签名"或"发了未签名的包"——这种包用户装不上，
而且常常到用户反馈才发现。这里在上传前强制校验签名，不通过直接中止。

二进制走 GitHub Release 附件，不提交进 git。

用法：
    python tools/release.py --bump          # 版本 patch +1
    python tools/release.py --version 0.2.0
    python tools/release.py --dry-run       # 只跑到校验签名，不提交不发版
"""
import argparse
import os
import re
import subprocess
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GRADLE = "gradlew.bat"
BUILD_GRADLE = os.path.join(REPO, "app", "build.gradle.kts")
APK = os.path.join(REPO, "app", "build", "outputs", "apk", "release", "app-release.apk")
REPO_SLUG = "hea784/SafeStream"

JAVA_HOME = r"D:\dev-tools\sdks\jdk-17.0.20.1+1"
ANDROID_HOME = r"D:\dev-tools\sdks\android-sdk"
APKSIGNER = os.path.join(ANDROID_HOME, "build-tools", "35.0.0", "apksigner.bat")
CREDENTIALS = r"D:\dev-tools\secrets\safestream\credentials.properties"

NOTES_HEADER = """## SafeStream v{version}

安装：下载 `app-release.apk` 传到手机点击安装。自签名应用会提示"未知来源"，
允许即可。要求 Android 7.0 (API 24) 及以上。

签名者：{dn}

完整变更见 [PROMPT.md](https://github.com/{slug}/blob/main/PROMPT.md) 与提交记录。
"""


def env():
    e = dict(os.environ)
    e["JAVA_HOME"] = JAVA_HOME
    e["ANDROID_HOME"] = ANDROID_HOME
    e["ANDROID_SDK_ROOT"] = ANDROID_HOME
    e["GRADLE_USER_HOME"] = r"D:\dev-tools\caches\gradle"
    return e


def run(args, **kw):
    p = subprocess.run(args, cwd=REPO, env=env(), capture_output=True,
                       text=True, encoding="utf-8", errors="replace", **kw)
    return p.returncode, p.stdout or "", p.stderr or ""


def fail(msg):
    print("FAIL: " + msg, file=sys.stderr)
    sys.exit(1)


def check_prereqs():
    if not os.path.exists(CREDENTIALS):
        fail("找不到签名凭据 " + CREDENTIALS + "（没有它只能产出装不上的未签名包）")
    if os.path.abspath(REPO) in os.path.abspath(CREDENTIALS):
        fail("签名凭据不能放在仓库目录内，会被提交上去")
    if not os.path.exists(APKSIGNER):
        fail("找不到 apksigner: " + APKSIGNER)


def read_versions():
    with open(BUILD_GRADLE, encoding="utf-8") as fh:
        text = fh.read()
    name = re.search(r'versionName\s*=\s*"([^"]+)"', text).group(1)
    code = int(re.search(r"versionCode\s*=\s*(\d+)", text).group(1))
    return text, name, code


def write_version(text, name, code):
    text = re.sub(r'versionName\s*=\s*"[^"]+"', 'versionName = "%s"' % name, text)
    text = re.sub(r"versionCode\s*=\s*\d+", "versionCode = %d" % code, text)
    with open(BUILD_GRADLE, "w", encoding="utf-8") as fh:
        fh.write(text)


def verify_signature(apk):
    rc, out, err = run([APKSIGNER, "verify", "--print-certs", apk])
    if rc != 0:
        fail("APK 未通过签名校验：%s" % (err or out)[:200])
    m = re.search(r"certificate DN:\s*(.+)", out)
    if not m:
        fail("读不到签名者信息，APK 可能未签名")
    return m.group(1).strip()
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--version")
    ap.add_argument("--bump", action="store_true")
    ap.add_argument("--dry-run", action="store_true",
                    help="只构建并校验签名，不提交、不发版")
    args = ap.parse_args()

    check_prereqs()
    text, cur_name, cur_code = read_versions()

    if args.bump:
        parts = cur_name.split(".")
        parts[2] = str(int(parts[2]) + 1) if len(parts) >= 3 else "1"
        version = ".".join(parts)
    else:
        version = args.version or cur_name
    next_code = cur_code + 1
    print("版本: %s (code %d)  ->  %s (code %d)" % (cur_name, cur_code, version, next_code))

    write_version(text, version, next_code)

    print("\n[1/4] 构建签名 APK ...")
    rc, out, err = run([GRADLE, "assembleRelease", "--console=plain", "-q"])
    if rc != 0:
        print(out[-3000:])
        fail("构建失败。版本号已改但未发布，如需回退请 git checkout app/build.gradle.kts")

    print("[2/4] 校验签名 ...")
    dn = verify_signature(APK)
    size_mb = round(os.path.getsize(APK) / 1024 / 1024, 2)
    print("      %s  (%.2f MB)" % (dn, size_mb))

    if args.dry_run:
        print("\n--dry-run：签名校验通过，未提交未发版。")
        return 0

    print("[3/4] 提交并打标签 ...")
    # 每一步都查返回码：这里曾因 push 静默失败，远端落后了 5 个版本没人发现
    def must(args, what):
        rc, out, err = run(args)
        if rc != 0:
            print(out[-1500:], err[-1500:])
            fail(what + " 失败（版本号已改、APK 已构建，但未同步到远端）")

    must(["git", "add", "-A"], "git add")
    must(["git", "commit", "-m", "release: v" + version], "git commit")
    must(["git", "tag", "-f", "v" + version], "git tag")
    must(["git", "push", "origin", "main"], "git push main")
    must(["git", "push", "--force", "origin", "v" + version], "git push tag")

    print("[4/4] 发布 GitHub Release ...")
    notes = os.path.join(os.environ.get("TEMP", "."), "safestream-notes.md")
    with open(notes, "w", encoding="utf-8") as fh:
        fh.write(NOTES_HEADER.format(version=version, dn=dn, slug=REPO_SLUG))
    rc, out, err = run(["gh", "release", "create", "v" + version, APK,
                        "--repo", REPO_SLUG,
                        "--title", "SafeStream v" + version,
                        "--notes-file", notes])
    if rc != 0:
        print(out, err)
        fail("Release 创建失败（APK 已构建但没上传上）")

    print("\n完成: https://github.com/%s/releases/tag/v%s" % (REPO_SLUG, version))
    return 0


if __name__ == "__main__":
    sys.exit(main())
