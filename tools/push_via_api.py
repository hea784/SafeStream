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


def gh_token():
    p = subprocess.run(["gh", "auth", "token"], capture_output=True, text=True)
    token = p.stdout.strip()
    if not token:
        raise SystemExit("取不到 gh token，请先 gh auth login")
    return token


TOKEN = gh_token()


def call(method, path, payload=None):
    url = API + "/repos/" + REPO + path
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", "Bearer " + TOKEN)
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("Content-Type", "application/json")
    req.add_header("User-Agent", "safestream-push")
    with urllib.request.urlopen(req, timeout=90) as resp:
        return json.loads(resp.read().decode("utf-8"))


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
            if call("GET", "/git/blobs/" + sha)["sha"] == sha:
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
    tree_body = {
        "base_tree": base,
        "tree": [{"path": p, "mode": m, "type": "blob", "sha": s} for m, s, p in entries],
    }
    tree = call("POST", "/git/trees", tree_body)
    local_tree = git("rev-parse", "HEAD^{tree}")
    if tree["sha"] != local_tree:
        raise SystemExit("tree 不一致，已中止：api=" + tree["sha"] + " local=" + local_tree)
    print("tree ok:", tree["sha"][:8])

    parents = git("rev-list", "--parents", "-n", "1", "HEAD").split()[1:]
    commit_body = {
        "message": git("log", "-1", "--pretty=%B", "HEAD"),
        "tree": tree["sha"],
        "parents": parents,
    }
    commit = call("POST", "/git/commits", commit_body)
    call("PATCH", "/git/refs/heads/main", {"sha": commit["sha"], "force": False})
    print("pushed:", commit["sha"][:8])
    if commit["sha"] != git("rev-parse", "HEAD"):
        print("提示：sha 不同是 GitHub 重写了 author/committer，内容已校验一致")
    return 0


if __name__ == "__main__":
    sys.exit(main())
