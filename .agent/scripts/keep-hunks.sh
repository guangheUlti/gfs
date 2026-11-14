#!/bin/bash
# 从 git diff 输出里只保留指定编号的 hunk，用于把同一文件的不同功能改动分批入库
# 用法: keep-hunks.sh <file> <keep-csv> <out-patch>
set -eu
file="$1"; keep="$2"; out="$3"
git diff -U3 -- "$file" > "$out.full"
awk -v keep="$keep" '
BEGIN { n = split(keep, a, ","); for (i=1;i<=n;i++) K[a[i]]=1 }
/^@@/ { h++ }
h == 0 { print; next }
{ if (K[h]) print }
' "$out.full" > "$out"
rm -f "$out.full"
