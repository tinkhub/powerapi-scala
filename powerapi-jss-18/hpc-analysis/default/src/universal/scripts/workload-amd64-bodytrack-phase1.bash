#!/bin/bash

rm -f bodytrack-1.log

${1}/pkgs/apps/bodytrack/inst/amd64-linux.gcc/bin/bodytrack ${1}/pkgs/apps/bodytrack/inputs/sequenceB_261 4 261 4000 5 0 1 &>bodytrack-1.log &

sleep 20

ps -ef | grep inst/ | grep -v grep | awk '{print $2}' | xargs kill -9 &>/dev/null

exit 0