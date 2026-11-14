#!/usr/bin/env bash
# Enforces the layer rules in enhancements/PROCESS.md.
#
#   check-layers.sh [<base-ref>]     every commit in <base-ref>..HEAD writes only what its layer owns
#                                    (default base: origin/dev)
#   check-layers.sh --open-questions fails while a question in any questions.md has no answer
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

open_questions() {
    local status=0 file number
    for file in enhancements/*/questions.md; do
        [[ -e $file ]] || continue
        while IFS= read -r number; do
            if ! grep -Eq "^## A${number}( |$)" "$file"; then
                echo "OPEN  $file  Q${number}: $(grep -E "^## Q${number}( |$)" "$file" | head -1)"
                status=1
            fi
        done < <(grep -Eo '^## Q[0-9]+' "$file" | grep -Eo '[0-9]+')
    done
    [[ $status -eq 0 ]] && echo "No open questions."
    return $status
}

# queue-guard -> queueguard: the feature's package name under .../enhancements/
package_of() { echo "${1//-/}"; }

# Is <path> owned by <layer> of <feature>?
owned() {
    local layer=$1 feature=$2 path=$3 pkg
    pkg=$(package_of "$feature")
    [[ $path == "enhancements/$feature/questions.md" ]] && return 0
    case $layer in
        spec) [[ $path == "enhancements/$feature/spec.md" ]] ;;
        plan) [[ $path == "enhancements/$feature/plan.md" ]] ;;
        test) [[ $path == app/src/test/*"/enhancements/$pkg/"* ||
                 $path == app/src/androidTest/*"/enhancements/$pkg/"* ]] ;;
        code) [[ $path == app/src/main/* ]] ;;
        *) return 1 ;;
    esac
}

# Is <path> owned by some layer? Commits without a layer prefix must stay out of these.
layer_owned() {
    local path=$1
    [[ $path =~ ^enhancements/[^/]+/(spec|plan|questions)\.md$ ]] && return 0
    [[ $path == app/src/main/* ]] && return 0
    [[ $path =~ ^app/src/(test|androidTest)/.*/enhancements/[^/]+/ ]] && return 0
    return 1
}

check_commits() {
    local base=$1 status=0 commit subject layer feature path deleted
    local prefix='^(spec|plan|test|code)\(([a-z0-9-]+)\): '

    while IFS= read -r commit; do
        subject=$(git log -1 --format=%s "$commit")
        layer="" feature=""
        if [[ $subject =~ $prefix ]]; then
            layer=${BASH_REMATCH[1]} feature=${BASH_REMATCH[2]}
        fi

        while IFS= read -r path; do
            [[ -n $path ]] || continue
            if [[ -n $layer ]]; then
                if ! owned "$layer" "$feature" "$path"; then
                    echo "FAIL  ${commit:0:9}  $layer($feature) may not write $path"
                    status=1
                fi
            elif layer_owned "$path"; then
                echo "FAIL  ${commit:0:9}  \"$subject\" has no layer prefix but writes $path"
                status=1
            fi

            # questions.md is a log: entries are added, never edited or removed
            if [[ $path == enhancements/*/questions.md ]]; then
                deleted=$(git show --numstat --format= "$commit" -- "$path" | awk '{print $2}')
                if [[ ${deleted:-0} != 0 ]]; then
                    echo "FAIL  ${commit:0:9}  $path is append-only ($deleted line(s) removed)"
                    status=1
                fi
            fi
        done < <(git show --name-only --format= "$commit")
    done < <(git rev-list --no-merges --reverse "$base..HEAD")

    [[ $status -eq 0 ]] && echo "Layer check passed for $base..HEAD."
    return $status
}

if [[ ${1:-} == --open-questions ]]; then
    open_questions
else
    check_commits "${1:-origin/dev}"
fi
