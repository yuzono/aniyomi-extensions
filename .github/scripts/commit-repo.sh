#!/bin/bash
set -e

rsync -a --delete --exclude .git --exclude .gitignore --exclude repo.json ../master/repo/ .
git config --global user.email "Anikku-Bot@users.noreply.github.com"
git config --global user.name "Anikku-Bot[bot]"
git status
if [ -n "$(git status --porcelain)" ]; then
    git add .
    git commit -m "Update extensions repo"
    git push

    # Purge cached index on jsDelivr
    curl https://purge.jsdelivr.net/gh/zosetsu-repo/ani-repo@repo/index.min.json
else
    echo "No changes to commit"
fi
