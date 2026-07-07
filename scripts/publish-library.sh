#!/usr/bin/env bash
# Builds typestring-annotations (android + iosArm64 + iosSimulatorArm64) and
# typestring-processor (jvm), then publishes them as a plain Maven repo committed to
# the `mvn-repo` branch of this repo's GitHub remote, servable via raw.githubusercontent.com.
#
# Must run on macOS: the iOS klibs require a Kotlin/Native toolchain, which only runs on macOS.
#
# Usage: scripts/publish-library.sh [remote]   (remote defaults to "origin")
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

REMOTE="${1:-origin}"
BRANCH="mvn-repo"
VERSION="$(sed -n 's/^typestring.version=//p' gradle.properties)"

if [[ -z "$VERSION" ]]; then
  echo "Could not read typestring.version from gradle.properties" >&2
  exit 1
fi

if [[ -n "$(git status --porcelain)" ]]; then
  echo "Working tree has uncommitted changes. Commit or stash before publishing." >&2
  exit 1
fi

WORKTREE_DIR="$(mktemp -d)"
rmdir "$WORKTREE_DIR"
cleanup() {
  git worktree remove "$WORKTREE_DIR" --force 2>/dev/null || true
  rm -rf "$WORKTREE_DIR"
}
trap cleanup EXIT

echo "==> Fetching existing $BRANCH from $REMOTE (if any)"
git fetch "$REMOTE" "$BRANCH" 2>/dev/null || true

if git show-ref --verify --quiet "refs/remotes/$REMOTE/$BRANCH"; then
  git worktree add "$WORKTREE_DIR" -B "$BRANCH" "$REMOTE/$BRANCH"
else
  echo "==> No existing $BRANCH branch found remotely, creating it"
  git worktree add --detach "$WORKTREE_DIR"
  (cd "$WORKTREE_DIR" && git checkout --orphan "$BRANCH" && git rm -rf . >/dev/null)
fi

echo "==> Building and publishing TypeString $VERSION"
./gradlew clean \
  :typestring-annotations:publishAllPublicationsToLocalRepoRepository \
  :typestring-processor:publishAllPublicationsToLocalRepoRepository \
  -PmavenRepoDir="$WORKTREE_DIR"

cd "$WORKTREE_DIR"
git add -A
if git diff --cached --quiet; then
  echo "==> Nothing new to publish - $VERSION is already up to date on $BRANCH"
  exit 0
fi

git commit -m "Publish TypeString $VERSION"
git push "$REMOTE" "HEAD:$BRANCH"

echo "==> Published. Consumers can now resolve:"
echo "    io.kshitij.typestring:typestring-annotations:$VERSION"
echo "    io.kshitij.typestring:typestring-processor:$VERSION"
echo "    from https://raw.githubusercontent.com/kshitijskumar/Kannotation/$BRANCH/"
