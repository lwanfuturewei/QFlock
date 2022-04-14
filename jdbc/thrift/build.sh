#!/bin/bash

pushd "$(dirname "$0")"
source ../docker/setup.sh
WORKING_DIR=$(pwd)
echo "WORKING_DIR: $WORKING_DIR"

if [ ! -d build ]; then
  # Create build directories.
  source ../docker/setup_build.sh
fi

if [ "$#" -gt 0 ]; then
  if [ "$1" == "-d" ]; then
    shift
    docker run --rm -it --name jdbc-build-debug \
      --network qflock-net \
      --mount type=bind,source="${WORKING_DIR}/../",target=/jdbc \
      -v "${WORKING_DIR}/build/.m2:${DOCKER_HOME_DIR}/.m2" \
      -v "${WORKING_DIR}/build/.gnupg:${DOCKER_HOME_DIR}/.gnupg" \
      -v "${WORKING_DIR}/build/.sbt:${DOCKER_HOME_DIR}/.sbt" \
      -v "${WORKING_DIR}/build/.cache:${DOCKER_HOME_DIR}/.cache" \
      -v "${WORKING_DIR}/build/.ivy2:${DOCKER_HOME_DIR}/.ivy2" \
      -u "${USER_ID}" \
      --entrypoint /bin/bash -w /jdbc \
      ${JDBC_DOCKER_NAME}
  fi
else
  echo "generating thrift api"
  docker run --rm -it --name jdbc-build \
    --network qflock-net \
    --mount type=bind,source="${WORKING_DIR}/../",target=/jdbc \
    -v "${WORKING_DIR}/build/.m2:${DOCKER_HOME_DIR}/.m2" \
    -v "${WORKING_DIR}/build/.gnupg:${DOCKER_HOME_DIR}/.gnupg" \
    -v "${WORKING_DIR}/build/.sbt:${DOCKER_HOME_DIR}/.sbt" \
    -v "${WORKING_DIR}/build/.cache:${DOCKER_HOME_DIR}/.cache" \
    -v "${WORKING_DIR}/build/.ivy2:${DOCKER_HOME_DIR}/.ivy2" \
    -u "${USER_ID}" \
    --entrypoint /jdbc/thrift/gen.sh -w /jdbc/thrift \
    ${JDBC_DOCKER_NAME}
fi
popd