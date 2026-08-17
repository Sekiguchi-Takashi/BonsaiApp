#!/data/data/com.termux/files/usr/bin/bash
cd "$(dirname "$0")"
if [ "$(pwd)" = "$HOME" ]; then printf 'ERROR: home では実行できません\n'; exit 1; fi
if [ ! -f app/build.gradle ] && [ ! -f app/build.gradle.kts ]; then printf 'ERROR: リポジトリ直下ではありません\n'; exit 1; fi
TOKEN=$(git config --global github.token)
GHUSER=Sekiguchi-Takashi
REPO=BonsaiApp
if [ ! -d .git ]; then git init -b main; fi
git remote remove origin 2>/dev/null
git remote add origin "https://${GHUSER}:${TOKEN}@github.com/${GHUSER}/${REPO}.git"
git add -A
git commit -m "${1:-update}" --allow-empty
git pull --rebase origin main
git push -u origin main || exit 1
git fetch --tags --force origin
LATEST=$(git tag -l 'v*' | sort -V | tail -1)
if [ -z "$LATEST" ]; then LATEST=v1.0.0; fi
bump() { printf '%s' "$1" | awk '/^v[0-9]+\.[0-9]+\.[0-9]+$/ { split($0,a,"."); sub("v","",a[1]); print "v" a[1] "." a[2] "." a[3]+1; next } /^v[0-9]+\.[0-9]+$/ { split($0,a,"."); sub("v","",a[1]); print "v" a[1] "." a[2] ".1"; next } /[0-9]+$/ { match($0,/[0-9]+$/); p=substr($0,1,RSTART-1); n=substr($0,RSTART)+1; print p n; next } { print "v1.0.0" }'; }
NEXT=$(bump "$LATEST")
while git rev-parse -q --verify "refs/tags/${NEXT}" >/dev/null 2>&1; do NEXT=$(bump "$NEXT"); done
git tag "$NEXT"
git push origin "$NEXT" || exit 1
printf 'pushed and tagged %s\n' "$NEXT"
