test -f "$1" || exit 1
FILE="$1"
Cleanup () {
  cat $$errs >&2
  rm -f $$errs "$FILE".new$$
}
trap Cleanup 0
xmllint --format "$1" 2>$$errs >"$1".new$$ || exit 1
grep . $$errs && exit 1
cmp -s "$1".new$$ "$1" && exit 0
echo $0 $1 >&2
mv "$1".new$$ "$1"
exit 0
