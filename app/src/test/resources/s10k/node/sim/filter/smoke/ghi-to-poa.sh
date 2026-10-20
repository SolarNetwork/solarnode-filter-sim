#!/bin/sh
# A stand-in for the sn-pvlib ghi-to-poa command: outputs a fixed POA value,
# and the arguments it was given.
printf '{"poa_global":812.5,"poa_direct":600,"args":"%s"}' "$*"
