#!/bin/sh
BUILD_INSTALLER=1
if [ "$1" = "--no-installer" ] || [ "$1" = "no-installer" ]; then
    BUILD_INSTALLER=0
    echo "Skipping native installer packaging."
fi

install_apt_packages() {
    if ! command -v apt-get >/dev/null 2>&1; then
        echo "Automatic dependency setup supports apt-based distributions only."
        return 1
    fi
    if [ "$(id -u)" -eq 0 ]; then
        apt-get update && apt-get install -y "$@"
    else
        sudo apt-get update && sudo apt-get install -y "$@"
    fi
}

# Java required every build. Linux packaging deps (apt) only on Linux hosts.
MISSING_TOOLS=
BUILD_JAVA_MISSING=0
APT_PACKAGES="openjdk-17-jdk"
case "$(uname -s 2>/dev/null || echo unknown)" in
    Linux*) IS_LINUX=1 ;;
    *) IS_LINUX=0 ;;
esac
if ! command -v java >/dev/null 2>&1; then
    MISSING_TOOLS=" java"
    BUILD_JAVA_MISSING=1
fi
if [ "$BUILD_INSTALLER" = "1" ] && [ "$IS_LINUX" = "1" ]; then
    APT_PACKAGES="$APT_PACKAGES curl file fakeroot flatpak flatpak-builder"
    for tool in jpackage curl file fakeroot flatpak flatpak-builder; do
        if ! command -v "$tool" >/dev/null 2>&1; then
            MISSING_TOOLS="$MISSING_TOOLS $tool"
        fi
    done
fi

if [ -n "$MISSING_TOOLS" ]; then
    if [ "$IS_LINUX" = "1" ]; then
        printf 'Missing build/package tools:%s. Install now? [y/N] ' "$MISSING_TOOLS"
        read -r INSTALL_DEPS
        if [ "$INSTALL_DEPS" = "y" ] || [ "$INSTALL_DEPS" = "Y" ]; then
            install_apt_packages $APT_PACKAGES || exit $?
        elif [ "$BUILD_JAVA_MISSING" = "1" ]; then
            echo "Cannot run Gradle build without Java."
            exit 1
        else
            BUILD_INSTALLER=0
            echo "Skipping native installer packaging because dependency installation was declined."
        fi
    elif [ "$BUILD_JAVA_MISSING" = "1" ]; then
        echo "Cannot run Gradle build without Java. Install a JDK 17+ and retry."
        exit 1
    fi
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
