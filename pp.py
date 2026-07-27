name: Android CI

on:
  push:
    branches:
      - "**"
  pull_request:
  workflow_dispatch:

permissions:
  contents: read

jobs:
  build:
    runs-on: ubuntu-latest

    steps:
      - name: Checkout repository
        uses: actions/checkout@v4
        with:
          fetch-depth: 0
          submodules: recursive

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "21"
          cache: gradle

      - name: Make Gradle executable
        run: chmod +x gradlew

      - name: Build (Debug Diagnostics)
        shell: bash
        run: |
          set +e

          echo "======================================================"
          echo "JAVA VERSION"
          echo "======================================================"
          java -version

          echo
          echo "======================================================"
          echo "GRADLE VERSION"
          echo "======================================================"
          ./gradlew --version

          echo
          echo "======================================================"
          echo "STARTING KOTLIN COMPILATION"
          echo "======================================================"

          ./gradlew \
            :app:compileFossMobileArm64DebugKotlin \
            --stacktrace \
            --info \
            2>&1 | tee build.log

          BUILD_EXIT=${PIPESTATUS[0]}

          echo
          echo "======================================================"
          echo "SECTION 1 - ALL KOTLIN ERRORS"
          echo "======================================================"
          grep -n "^e:" build.log || true

          echo
          echo "======================================================"
          echo "SECTION 2 - MusicService.kt"
          echo "======================================================"
          grep -n "MusicService.kt" build.log || true

          echo
          echo "======================================================"
          echo "SECTION 3 - MainActivity.kt"
          echo "======================================================"
          grep -n "MainActivity.kt" build.log || true

          echo
          echo "======================================================"
          echo "SECTION 4 - MediaLibrarySessionCallback.kt"
          echo "======================================================"
          grep -n "MediaLibrarySessionCallback.kt" build.log || true

          echo
          echo "======================================================"
          echo "SECTION 5 - ArchiveTuneMediaNotificationProvider.kt"
          echo "======================================================"
          grep -n "ArchiveTuneMediaNotificationProvider.kt" build.log || true

          echo
          echo "======================================================"
          echo "SECTION 6 - EqualizerPlaybackController.kt"
          echo "======================================================"
          grep -n "EqualizerPlaybackController.kt" build.log || true

          echo
          echo "======================================================"
          echo "SECTION 7 - UNRESOLVED REFERENCES"
          echo "======================================================"
          grep -n "Unresolved reference" build.log || true

          echo
          echo "======================================================"
          echo "SECTION 8 - EXCEPTIONS"
          echo "======================================================"
          grep -n "Exception" build.log || true

          echo
          echo "======================================================"
          echo "SECTION 9 - BUILD RESULT"
          echo "======================================================"
          echo "Exit Code: ${BUILD_EXIT}"

          exit ${BUILD_EXIT}

      - name: Upload Build Log
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: kotlin-build-log
          path: build.log

      - name: Assemble Debug APK
        if: success()
        run: ./gradlew assembleDebug

      - name: Upload Debug APK
        if: success()
        uses: actions/upload-artifact@v4
        with:
          name: debug-apk
          path: |
            **/build/outputs/apk/**/*.apk
