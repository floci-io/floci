#!/usr/bin/env bash
# Moves a branch that forked before the package rename (io.github.hectorvent.floci to io.floci.aws)
# onto a main that has it, so a pull request crosses the rename without a conflict in every file
# it touches.
#
# It runs rename_package.py apply on the branch's merge-base and on its tip, commits both results
# in a scratch worktree (the tip's as a child of the merge-base's), and cherry-picks the renamed tip
# onto the target. That cherry-pick is an ordinary three-way merge whose base is the renamed
# merge-base, so the only conflicts left are real ones: changes main made to the lines the branch
# also changed. The branch's commits become one commit (pull requests are squash-merged anyway)
# carrying the original messages in order, sign-offs included, and the first commit's author.
#
# The rename tool is taken from the target, so this needs only git, bash and python3.
#
# Usage, from any checkout of the repository, with the branch checked out or not:
#     git fetch origin
#     bash <(git show origin/main:tools/rename/rebase_across_rename.sh) <branch> [<target>]
#
# <target> defaults to origin/main. The branch is rewritten in place and its old tip kept as
# <branch>-before-rename. Nothing is pushed: check the result, then
#     git push --force-with-lease origin <branch>
set -euo pipefail

NEW_SOURCE_ROOT=src/main/java/io/floci/aws
PYTHON="${PYTHON:-python3}"

die() {
    echo "rebase_across_rename: $*" >&2
    exit 1
}

[ $# -ge 1 ] && [ $# -le 2 ] || die "usage: rebase_across_rename.sh <branch> [<target>]"
branch=$1
target=${2:-origin/main}
backup="$branch-before-rename"

toplevel=$(git rev-parse --show-toplevel 2>/dev/null) || die "run it inside a checkout of the repository"
# Pathspecs and git archive paths are relative to the current directory.
cd "$toplevel"
old_tip=$(git rev-parse --verify --quiet "refs/heads/$branch^{commit}") || die "no local branch $branch"
target_sha=$(git rev-parse --verify --quiet "$target^{commit}") || die "unknown target $target"
command -v "$PYTHON" >/dev/null 2>&1 || die "$PYTHON not found (set PYTHON to a Python 3 interpreter)"

# The first commit on the target's first-parent line that touches the new source root is the cut's
# move commit; rebase-merge lands the cut's two commits on that line.
cut=$(git rev-list --first-parent --reverse "$target_sha" -- "$NEW_SOURCE_ROOT" | sed -n 1p)
[ -n "$cut" ] || die "$target has no $NEW_SOURCE_ROOT: the rename has not landed there"
if git merge-base --is-ancestor "$cut" "$old_tip"; then
    die "$branch already contains the rename ($(git rev-parse --short "$cut")); rebase it normally"
fi
merge_base=$(git merge-base "$old_tip" "$cut")
[ "$(git rev-list --count --no-merges "$merge_base..$old_tip")" -gt 0 ] || die "$branch has no commits of its own"

if existing=$(git rev-parse --verify --quiet "refs/heads/$backup"); then
    [ "$existing" = "$old_tip" ] || die "$backup already exists and points elsewhere; delete or rename it first"
fi

checked_out_here=false
if [ "$(git symbolic-ref --quiet HEAD 2>/dev/null || true)" = "refs/heads/$branch" ]; then
    checked_out_here=true
    [ -z "$(git status --porcelain --untracked-files=no)" ] || die "$branch has uncommitted changes; commit or stash them"
elif git worktree list --porcelain | grep -F -x "branch refs/heads/$branch" >/dev/null; then
    die "$branch is checked out in another worktree; switch that worktree to another branch first"
fi

work=$(mktemp -d "${TMPDIR:-/tmp}/rebase-across-rename.XXXXXX")
tree="$work/tree"
keep_tree=false
cleanup() {
    if [ "$keep_tree" = false ]; then
        git worktree remove --force "$tree" >/dev/null 2>&1 || true
        rm -rf "$work"
    fi
}
trap cleanup EXIT

mkdir -p "$work/tool"
git archive "$target_sha" tools/rename | tar -x -C "$work/tool"

# Plumbing commits only: they are never pushed, and the user's identity, hooks and signing do not
# belong on them.
internal_commit() {
    GIT_AUTHOR_NAME=rebase_across_rename GIT_AUTHOR_EMAIL=rebase_across_rename@localhost \
    GIT_COMMITTER_NAME=rebase_across_rename GIT_COMMITTER_EMAIL=rebase_across_rename@localhost \
        git commit-tree "$@"
}

renamed_tree() {
    git -C "$tree" checkout --quiet --detach "$1"
    "$PYTHON" "$work/tool/tools/rename/rename_package.py" --root "$tree" apply >"$work/apply.log" 2>&1 \
        || { cat "$work/apply.log" >&2; die "rename_package.py apply failed on $(git rev-parse --short "$1")"; }
    git -C "$tree" add --all
    git -C "$tree" write-tree
}

echo "Renaming $branch ($(git rev-parse --short "$old_tip")) and its merge-base ($(git rev-parse --short "$merge_base")) with the tool from $target"
git worktree add --quiet --detach "$tree" "$merge_base"
base_tree=$(renamed_tree "$merge_base")
base_renamed=$(internal_commit "$base_tree" -p "$merge_base" -m "renamed merge-base")
git -C "$tree" reset --quiet --hard

first=$(git rev-list --reverse --no-merges "$merge_base..$old_tip" | sed -n 1p)
git log --reverse --no-merges --format=%B "$merge_base..$old_tip" >"$work/message"
tip_tree=$(renamed_tree "$old_tip")
tip_renamed=$(GIT_AUTHOR_NAME=$(git log -1 --format=%an "$first") GIT_AUTHOR_EMAIL=$(git log -1 --format=%ae "$first") \
    git commit-tree "$tip_tree" -p "$base_renamed" -F "$work/message")
git -C "$tree" reset --quiet --hard

git -C "$tree" checkout --quiet --detach "$target_sha"
if ! git -C "$tree" cherry-pick "$tip_renamed" >"$work/cherry-pick.log" 2>&1; then
    if [ -z "$(git -C "$tree" diff --name-only --diff-filter=U)" ] && git -C "$tree" diff --cached --quiet; then
        git -C "$tree" cherry-pick --abort >/dev/null 2>&1 || true
        die "everything $branch changes is already in $target (merged?); nothing to replay, $branch is unchanged"
    fi
    keep_tree=true
    git branch --force "$backup" "$old_tip"
    cat "$work/cherry-pick.log" >&2
    cat >&2 <<EOF

rebase_across_rename: the renamed branch conflicts with $target in the files above.
Resolve them in the scratch worktree, then move the branch onto the result:
    cd $tree
    git add <files> && git cherry-pick --continue
    cd $toplevel
EOF
    if [ "$checked_out_here" = true ]; then
        echo "    git reset --keep \$(git -C $tree rev-parse HEAD)" >&2
    else
        echo "    git branch --force $branch \$(git -C $tree rev-parse HEAD)" >&2
    fi
    echo "    git worktree remove $tree" >&2
    echo "The old tip is kept as $backup." >&2
    exit 1
fi
new_tip=$(git -C "$tree" rev-parse HEAD)

git branch --force "$backup" "$old_tip"
if [ "$checked_out_here" = true ]; then
    git reset --quiet --keep "$new_tip"
else
    git branch --force "$branch" "$new_tip"
fi

echo "$branch is now $(git rev-parse --short "$new_tip") on $target ($(git rev-parse --short "$target_sha")); the old tip is $backup."
echo "Check it, then: git push --force-with-lease origin $branch"
