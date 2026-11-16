#!/bin/bash

chmod -R 777 .build 2>/dev/null
rm -rf .build 2>/dev/null

if [ -z "$GOPATH" ]; then
    GOPATH=$(go env GOPATH)
fi

# Install gomobile
# Pinned to a fixed commit (M29): tracking origin/master2 meant every
# build could silently pick up a different gomobile. This sha is the
# master2 HEAD as of 2026-11-07 (commit 2025-08-30, unchanged for over
# a year) — exactly what the recent successful builds used.
GOMOBILE_COMMIT=17d6af34f6bd6d7e1e428e0c652c8b54a46bda4f
if [ ! -f "$GOPATH/bin/gomobile-matsuri" ]; then
    git clone https://github.com/MatsuriDayo/gomobile.git
    pushd gomobile
	git checkout "$GOMOBILE_COMMIT"
    pushd cmd
    pushd gomobile
    go install -v
    popd
    pushd gobind
    go install -v
    popd
    popd
    rm -rf gomobile
    mv "$GOPATH/bin/gomobile" "$GOPATH/bin/gomobile-matsuri"
    mv "$GOPATH/bin/gobind" "$GOPATH/bin/gobind-matsuri"
fi

GOBIND=gobind-matsuri gomobile-matsuri init
