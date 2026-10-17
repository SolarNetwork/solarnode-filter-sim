#!/usr/bin/env sh

set -e

PROJ_HOME="${PROJ_HOME:-$(dirname "$0")/../..}"
DRY_RUN=""

. semver.sh

while getopts ":n" opt; do
	case $opt in
		n) DRY_RUN="1";;
		*)
			echo "Unknown argument -${OPTARG}"
			exit 1
	esac
done
shift $(($OPTIND - 1))

version="$1"
if [ -z "$version" ]; then
	echo 'Pass release version as argument.'
	exit 1
fi

# portable sed -i
case $(sed --help 2>&1) in
  *GNU*) sed_i () { sed -i "$@"; };;
  *) sed_i () { sed -i '' "$@"; };;
esac

updateVersions () {
	for f in build.gradle; do
		echo "RELEASE: Update $f version to $1"
		if [ ! -e "$f" ]; then
			echo "ERROR: missing file $f" >&2
			echo "Looking in PROJ_HOME: $PROJ_HOME" >&2
			exit 1
		elif [ -z "$DRY_RUN" ]; then
			sed_i -e 's/^\([[:space:]]*\)version = .*/\1version = '"'$1'"'/' "$f"
		fi
	done
}

nextDevRelease () {
    local major=0
    local minor=0
    local patch=0
    local special=0
    semverParseInto $1 major minor patch special
    echo "$major.$minor.$(($patch + 1)).dev0"
}

doRelease () {
	cd "$PROJ_HOME"
	if [ -n "$DRY_RUN" ]; then
		echo "RELEASE: DRY RUN: no changes will be made."
	fi

	echo "RELEASE: start release $version"
	if [ -z "$DRY_RUN" ]; then
		git flow release start $version
	fi

	updateVersions $version

	echo "RELEASE: commit release version changes."
	if [ -z "$DRY_RUN" ]; then
		git add .
		git commit -S -m 'Bump version for next release.'
		git flow release finish --sign --push
		git switch develop
	fi

	updateVersions "$(nextDevRelease $version)"

	echo "RELEASE: commit development version changes."
	if [ -z "$DRY_RUN" ]; then
		git add .
		git commit -S -m 'Bump version for next development cycle.'
		git push
	fi

	echo "RELEASE: switch to main for publish."
	if [ -z "$DRY_RUN" ]; then
		git switch main
	fi
}

(doRelease)
