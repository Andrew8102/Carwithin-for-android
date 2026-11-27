#!/usr/bin/env bash
# 把当前项目推送到已经创建好的 GitHub 仓库。
# 用法：
#   1) 已配置 Git 凭据（credential manager / SSH）时：直接 ./scripts/push_to_github.sh
#   2) 用 token 时：GITHUB_TOKEN=xxx ./scripts/push_to_github.sh
# token 只在命令行中临时使用，不会写入 remote 配置或项目文件。
set -euo pipefail

OWNER="Andrew8102"
REPO="Carwithin-for-android"
REPO_URL="https://github.com/${OWNER}/${REPO}.git"

cd "$(dirname "$0")/.."

if [[ ! -d .git ]]; then
  git init -b main
fi

git add -A
if ! git diff --cached --quiet; then
  git -c user.name="${GIT_AUTHOR_NAME:-Andrew8102}" \
      -c user.email="${GIT_AUTHOR_EMAIL:-Andrew8102@users.noreply.github.com}" \
      commit -m "${1:-Update project}"
fi

# 远端地址保持干净，不带 token
git remote remove origin 2>/dev/null || true
git remote add origin "$REPO_URL"

if [[ -n "${GITHUB_TOKEN:-}" ]]; then
  PUSH_URL="https://x-access-token:${GITHUB_TOKEN}@github.com/${OWNER}/${REPO}.git"
  git push "$PUSH_URL" HEAD:main
else
  git push -u origin main
fi

echo "已推送：https://github.com/${OWNER}/${REPO}"
