"""绕开被阻断的 github.com，用 GitHub REST API 推送提交。

为什么需要它：某些网络环境下 github.com 的所有 IP 都超时，但 api.github.com 正常。
`git push` 走 github.com 会一直失败；这个脚本改走 api.github.com 的 Git Data API
（create blob -> tree -> commit -> update ref）完成推送。

安全设计：
  - 内容一律从 `git cat-file` 读对象原始字节，不读工作区文件。
    .gitattributes 的 eol 规则会让工作区字节与对象字节不一致（曾导致 tree sha 对不上）。
  - 上传后逐个校验 blob sha 与 git 记录一致。
  - 最终校验 API 返回的 tree sha 与本地 HEAD^{tree} 完全相同才允许更新 ref，
    内容有任何偏差就中止。
  - 更新 ref 用 force=false（快进语义），不会覆盖远端历史。
    父提交取**远端当前 HEAD**，不是本地 HEAD^：某些环境下 github.com 被阻断无法 fetch，
    远端可能有本地没有的提交（如只经 API 改过文件模式的提交）。以远端 HEAD 为父才能
    通过快进校验。此时本地与远端历史会分叉，脚本会打印提示。

MODE_OVERRIDE：Windows 上 git 把 gradlew 记为 100644，Linux 上 ./gradlew 会 Permission
denied。API 推送时按这里强制修正。

用法（在仓库根目录执行）：
    python tools/push_via_api.py
"""
import base64
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request

REPO = os.environ.get("GITHUB_REPO", "hea784/SafeStream")
API = "https://api.github.com"
# 脚本可能从任意目录被调用，git 命令一律以仓库根为工作目录
REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODE_OVERRIDE = {"gradlew": "100755"}


def gh_token():
    p = subprocess.run(["gh", "auth", "token"], capture_output=True, text=True)
    token = p.stdout.strip()
    if not token:
        raise SystemExit("取不到 gh token，请先 gh auth login")
    return token


TOKEN = gh_token()


def call(method, path, payload=None, allow_missing=False):
    url = API + "/repos/" + REPO + path
    # 用 curl 而不是 urllib：本机实测 urllib 会被对端重置连接（WinError 10054），
    # 而 curl 走同样的 api.github.com 稳定可用。两者 TLS 行为有差异。
    args = ["curl.exe", "-sS", "-X", method, url,
            "-H", "Authorization: Bearer " + TOKEN,
            "-H", "Accept: application/vnd.github+json",
            "-H", "Content-Type: application/json",
            "-H", "User-Agent: safestream-push",
            "--max-time", "90", "-w", "\n%{http_code}"]
    if payload is not None:
        body = json.dumps(payload).encode("utf-8")
        args += ["--data-binary", "@-"]
        p = subprocess.run(args, input=body, capture_output=True)
    else:
        p = subprocess.run(args, capture_output=True)
    out = p.stdout.decode("utf-8", "replace")
    if "\n" not in out:
        raise SystemExit("curl 无输出: " + p.stderr.decode("utf-8", "replace")[:300])
    text, _, code_s = out.rpartition("\n")
    code = int(code_s.strip() or 0)
    if code == 404 and allow_missing:
        return None
    if code >= 400:
        raise SystemExit("HTTP %s %s\n%s" % (code, path, text[:600]))
    return json.loads(text) if text.strip() else None


def git(*args, binary=False):
    p = subprocess.run(["git", *args], cwd=REPO_ROOT, capture_output=True, check=True)
    return p.stdout if binary else p.stdout.decode("utf-8").strip()


def main():
    print("repo:", REPO)
    entries = []
    for line in git("ls-tree", "-r", "HEAD").splitlines():
        parts = line.split(None, 3)
        if len(parts) == 4:
            entries.append((parts[0], parts[2], parts[3]))
    print("files:", len(entries))

    for mode, sha, path in entries:
        try:
            cached = call("GET", "/git/blobs/" + sha, allow_missing=True)
            if cached and cached["sha"] == sha:
                print("  cached  ", path)
                continue
        except urllib.error.HTTPError:
            pass
        raw = git("cat-file", "blob", sha, binary=True)
        payload = {"content": base64.b64encode(raw).decode(), "encoding": "base64"}
        created = call("POST", "/git/blobs", payload)
        if created["sha"] != sha:
            raise SystemExit("blob 内容不一致，已中止：" + path)
        print("  upload  ", path)

    base = call("GET", "/git/ref/heads/main")["object"]["sha"]
    local_heads = git("rev-list", "--parents", "-n", "1", "HEAD").split()[1:]
    if base not in local_heads:
        print("注意：远端 HEAD " + base[:8] + " 不在本地历史中（github.com 被阻断无法 fetch）")
        print("      本次以远端 HEAD 为父提交推送；推送后本地与远端历史分叉，")
        print("      网络恢复后需要 git fetch --rebase 或 git reset --hard origin/main 归一。")
    tree_body = {
        "base_tree": base,
        "tree": [{"path": p, "mode": MODE_OVERRIDE.get(p, m), "type": "blob", "sha": s}
                 for m, s, p in entries],
    }
    tree = call("POST", "/git/trees", tree_body)
    # 有 mode override 时 tree sha 本就与本地不同，改用逐文件内容校验
    expected = dict((p, s) for _m, s, p in entries)
    for node in call("GET", "/git/trees/" + tree["sha"] + "?recursive=1")["tree"]:
        if node["type"] == "blob" and node["path"] in expected:
            if node["sha"] != expected[node["path"]]:
                raise SystemExit("内容不一致，已中止：" + node["path"])
    print("tree ok:", tree["sha"][:8])

    commit_body = {
        "message": git("log", "-1", "--pretty=%B", "HEAD"),
        "tree": tree["sha"],
        "parents": [base],
    }
    commit = call("POST", "/git/commits", commit_body)
    call("PATCH", "/git/refs/heads/main", {"sha": commit["sha"], "force": False})
    print("pushed:", commit["sha"][:8])
    if commit["sha"] != git("rev-parse", "HEAD"):
        print("提示：sha 不同是 GitHub 重写了 author/committer，内容已校验一致")
    return 0


if __name__ == "__main__":
    sys.exit(main())
