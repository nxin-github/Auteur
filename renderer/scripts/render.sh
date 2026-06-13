#!/bin/bash
# backend 调用入口:接受输出路径和 props 路径,先确保 chrome 就绪,再渲染。
# 用法: bash scripts/render.sh <output.mp4> <props.json> [composition_id]
#   composition_id 可选,默认 LifeCopy(横屏 1920×1080;StoryHorizontal 的 alias)。
set -e

if [ $# -lt 2 ]; then
  echo "Usage: $0 <output.mp4> <props.json> [composition_id]" >&2
  exit 2
fi

OUTPUT="$1"
PROPS="$2"
COMPOSITION="${3:-LifeCopy}"

# 切到 renderer 根目录(脚本可能从任意 cwd 调用)
cd "$(dirname "$0")/.."

# 首次跑会下载 + 清隔离标记;已存在直接跳过
bash scripts/setup-chrome.sh

# 透传给 remotion render
# --timeout=300000:Remotion 全局 delayRender 超时(默认 30s)。
#   per-Img 的 delayRenderTimeoutInMilliseconds 不能单独覆盖此项,必须 CLI 一并提。
exec npx remotion render src/index.ts "${COMPOSITION}" "${OUTPUT}" --props="${PROPS}" --timeout=300000
