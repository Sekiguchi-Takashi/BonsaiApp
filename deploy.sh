#!/data/data/com.termux/files/usr/bin/bash
cd "$(dirname "$0")"
TOKEN=$(git config --global github.token)
GHUSER=Sekiguchi-Takashi
REPO=BonsaiApp
API=https://api.github.com/repos/${GHUSER}/${REPO}
if [ ! -d .git ]; then git init -b main; fi
git remote remove origin 2>/dev/null
git remote add origin "https://${GHUSER}:${TOKEN}@github.com/${GHUSER}/${REPO}.git"
git add -A
git commit -m "${1:-update}"
git pull --rebase origin main
git push -u origin main
if [ "$2" = "notag" ]; then
  printf 'pushed (notag)\n'
  exit 0
fi
LATEST=$(git ls-remote --tags origin | cut -f2 | sed 's|refs/tags/||' | grep -v '\^{}' | sort -V | tail -1)
NEXT=$(printf '%s' "$LATEST" | awk '/^v[0-9]+\.[0-9]+\.[0-9]+$/ { split($0,a,"."); sub("v","",a[1]); print "v" a[1] "." a[2] "." a[3]+1; next } /^v[0-9]+\.[0-9]+$/ { split($0,a,"."); sub("v","",a[1]); print "v" a[1] "." a[2] ".1"; next } /[0-9]+$/ { match($0,/[0-9]+$/); p=substr($0,1,RSTART-1); n=substr($0,RSTART)+1; print p n; next } { print "v1.0.0" }')
if [ -z "$NEXT" ]; then NEXT=v1.0.0; fi
SHA=$(git rev-parse HEAD)
curl -s -o /dev/null -H "Authorization: token ${TOKEN}" -d "{\"ref\":\"refs/tags/${NEXT}\",\"sha\":\"${SHA}\"}" "${API}/git/refs"
printf 'pushed and tagged %s\n' "$NEXT"
