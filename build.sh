#!/bin/sh
BUILD_INSTALLER=1
if [ "$1" = "--no-installer" ] || [ "$1" = "no-installer" ]; then
    BUILD_INSTALLER=0
    echo "Skipping native installer packaging."
fi

./gradlew clean build
EXIT_CODE=$?
if [ $EXIT_CODE -ne 0 ]; then
    read -p "Press any key to continue..."
    exit $EXIT_CODE
fi

if [ "$BUILD_INSTALLER" = "1" ]; then
    echo "Packaging native installer..."
    ./gradlew package
    EXIT_CODE=$?
fi

read -p "Press any key to continue..."
exit $EXIT_CODE
