#!/bin/bash

set -e # exit on error
pushd "$(dirname "$0")" # connect to root

ROOT_DIR=$(pwd)
echo "ROOT_DIR ${ROOT_DIR}"

USER_NAME=${SUDO_USER:=$USER}

# Initialize tpc-ds on dc1
${ROOT_DIR}/benchmark/src/docker-bench.py --init

# Copy data to dc2
${ROOT_DIR}/storage/init_dc2.sh

