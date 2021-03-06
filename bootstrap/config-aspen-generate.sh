#!/bin/sh
# $Id$
# A script to process config-aspen.xml.src into a valid config-aspen.xml
# This preprocessing step avoids a lot of redundancy when dealing with multiple
# targets that are practically the same.

usage() {
    echo "Usage: $0 [--help] build-targets..."
    echo "e.g. $0 dev stable"
}

builds=
while [ -n "$1" ]; do
    if [ "$1" == "--help" -o "$1" == "-h" ]; then
        usage
        exit -1
    fi
    if [ "${1#--}" != "$1" ]; then
        echo "***** invalid option: $1"
        usage
        exit -1
    fi
    builds="$builds $1"
    shift;
done
if [ "$builds" == "" ]; then
    echo "***** must specify at least one build target"
    usage
    exit -1
fi

at=ASPENTARGET
p4s=P4SUFFIX
hlt=HEADERLINETOKEN
hlttext="<!-- Generated by config-aspen-generate.sh on `date`; build targets:$builds -->"

rm -f config-aspen.xml
for p4 in $builds; do
	for t in aspen-debug aspen-opto; do
		sed "s/${at}/$t/g" config-aspen.xml.src | sed "s/.*${hlt}.*/${hlttext}/g" | sed "s/${p4s}/$p4/g" >> config-aspen.xml
	done;
done;
