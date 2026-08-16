# Airgate — Watchdog for Air-Gapped Android

# --- Defaults ---
GRADLE      ?= ./gradlew
JAVA_HOME   ?= $(shell test -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" && echo "/Applications/Android Studio.app/Contents/jbr/Contents/Home")
SDK         ?= $(shell cat local.properties 2>/dev/null | sed -n 's/^sdk\.dir=//p' || echo "$$ANDROID_HOME")
ADB         ?= $(SDK)/platform-tools/adb
EMULATOR    ?= $(SDK)/emulator/emulator
BUILD_TOOLS ?= $(shell ls -d "$(SDK)"/build-tools/* 2>/dev/null | sort -V | tail -1)
AAPT        ?= $(BUILD_TOOLS)/aapt
APKSIGNER   ?= $(BUILD_TOOLS)/apksigner
AVD         ?= s4_dev
EMULATOR_SCRIPT ?= tools/start-emulator.sh
APP_ID      ?= com.airgate
APK         ?= app/build/outputs/apk/debug/app-debug.apk
APK_RELEASE ?= $(firstword $(wildcard app/build/outputs/apk/release/*.apk))
EMULATOR_DEVICE = $(shell $(ADB) devices 2>/dev/null | awk '$$2=="device" && $$1 ~ /^emulator-/ {print $$1; exit}')
PHONE_DEVICE    = $(shell $(ADB) devices 2>/dev/null | awk '$$2=="device" && $$1 !~ /^emulator-/ {print $$1; exit}')

export JAVA_HOME

.PHONY: help build release verify-release unit android-test lint install install-android-phone update launch verify clean logcat screens screens-dark mockups mockups-only emulator emulator-start emulator-stop

help:
	@echo "Airgate development commands"
	@echo "  make build            assemble the debug APK"
	@echo "  make release          build the release APK and print its SHA-256"
	@echo "  make verify-release   build release APK and verify permissions + signature"
	@echo "  make unit             run JVM unit tests"
	@echo "  make android-test     run instrumented tests on connected device/emulator"
	@echo "  make lint             run Android lint on the debug variant"
	@echo "  make install          build + install the app on the emulator"
	@echo "  make install-android-phone  build + install on a physical phone"
	@echo "  make update           rebuild and update installed app"
	@echo "  make launch           install/update and launch the app on the emulator"
	@echo "  make verify           full Phase gate: unit + lint + build"
	@echo "  make clean            delete Gradle build outputs"
	@echo "  make screens          capture light-theme screenshots (emulator capture)"
	@echo "  make screens-dark     capture dark-theme screenshots (emulator capture)"
	@echo "  make mockups          wrap all screenshots in the Android phone mockup"
	@echo "  make mockups-only     re-wrap existing screenshots (no recapture)"
	@echo "  make logcat           stream app logcat output from the emulator"
	@echo "  make emulator         boot the $(AVD) emulator (no-op if a device is connected)"
	@echo "  make emulator-stop    kill the running emulator"
	@echo
	@echo "make install targets the emulator only; make install-android-phone targets a physical phone only."

build:
	$(GRADLE) :app:assembleDebug

release:
	$(GRADLE) :app:assembleRelease
	@echo "Release APK: $(APK_RELEASE)"
	@shasum -a 256 $(APK_RELEASE)

verify-release: release
	@echo "== permissions =="
	@$(AAPT) dump permissions app/build/outputs/apk/release/*.apk
	@echo "== permissions audit =="
	@if $(AAPT) dump permissions app/build/outputs/apk/release/*.apk | grep -i "android.permission.INTERNET"; then \
		echo "ERROR: INTERNET permission found in release APK!"; exit 1; \
	else \
		echo "SUCCESS: Absolute zero network purity verified (no INTERNET permission)."; \
	fi

unit:
	$(GRADLE) :buildSrc:test :app:testDebugUnitTest

android-test: emulator
	$(GRADLE) :app:connectedDebugAndroidTest

lint:
	$(GRADLE) :app:lintDebug

install: build
	@test -n "$(EMULATOR_DEVICE)" || { echo "error: no emulator connected — boot one with 'make emulator'"; exit 1; }
	@echo "Installing onto emulator: $(EMULATOR_DEVICE)"
	$(ADB) -s "$(EMULATOR_DEVICE)" install -r "$(APK)"

install-android-phone: build
	@test -n "$(PHONE_DEVICE)" || { \
		echo "error: no physical phone available for install"; \
		echo ""; \
		$(ADB) devices -l 2>/dev/null | grep -v '^List of devices attached' | grep -v '^$$' | while read line; do \
			device=$$(echo $$line | awk '{print $$1}'); \
			state=$$(echo $$line | awk '{print $$2}'); \
			case "$$state" in \
				unauthorized) echo "  ! $$device  is connected but UNauthorized — accept the \"Allow USB debugging\" prompt on the phone (developer options)";; \
				offline)      echo "  ! $$device  is connected but offline — replug the USB cable / check drivers";; \
				*)            echo "  ? $$line";; \
			esac; \
		done; \
		if $(ADB) devices 2>/dev/null | grep -v '^List of devices attached' | grep -v '^$$' | grep -qv 'emulator'; then \
			echo "  hint: 'Block Debugging Features' must be OFF in Airgate settings to restore ADB"; \
		fi; \
		exit 1; \
	}
	@echo "Installing onto physical Android phone: $(PHONE_DEVICE)"
	$(ADB) -s "$(PHONE_DEVICE)" install -r "$(APK)"
	@echo "== Provisioning Airgate background service on physical phone =="
	$(ADB) -s "$(PHONE_DEVICE)" shell dumpsys deviceidle whitelist +$(APP_ID) || true
	$(ADB) -s "$(PHONE_DEVICE)" shell am start-foreground-service $(APP_ID)/.service.WatchdogService || true
	$(ADB) -s "$(PHONE_DEVICE)" shell am start -n $(APP_ID)/.MainActivity

update: install

launch: install
	$(ADB) -s "$(EMULATOR_DEVICE)" shell am start -n $(APP_ID)/.MainActivity

verify: unit lint build

clean:
	$(GRADLE) clean

# Capture README + user-guide screenshots. adb screencap returns black because
# the app sets FLAG_SECURE, but the emulator's own screenshot (`adb emu
# screenrecord screenshot`) captures the real display including the system bars,
# so we drive the app with an instrumented test that parks on each view (it
# writes a marker file). The test is run in the background (`&`) so each
# wait_and_shot below polls for its marker while the test is still parked on the
# screen, then screencaps the emulator straight to the host filesystem. Theme is
# set via `cmd uimode night`.
ANDROID_TEST_APK ?= app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

# Wait up to 60s for the test to park on view $1, then screenshot it to $2.
# Screenshots always target the emulator (`adb emu` works only there).
define wait_and_shot
	n=0; until $(ADB) -s "$(EMULATOR_DEVICE)" shell "run-as $(APP_ID) test -f files/$(strip $(1)).park" >/dev/null 2>&1; do \
		n=$$((n+1)); [ $$n -gt 200 ] && { echo "error: timed out waiting for '$(strip $(1))'"; exit 1; }; \
		sleep 0.3; \
	done; \
	$(ADB) -s "$(EMULATOR_DEVICE)" shell "input keyevent 111" >/dev/null 2>&1; \
	sleep 0.5; \
	mkdir -p /tmp/ag-shot-$(strip $(1)); \
	$(ADB) -s "$(EMULATOR_DEVICE)" emu screenrecord screenshot /tmp/ag-shot-$(strip $(1)) >/dev/null 2>&1; \
	mv /tmp/ag-shot-$(strip $(1))/Screenshot_*.png $(strip $(2))
endef

# Capture the full set of light-theme screenshots: art/screens/screen-light.png
# for the README hero shot, and art/screens/guide/*-light.png for user-guide.md.
screens: emulator build
	@mkdir -p art/screens/guide
	@$(GRADLE) :app:assembleDebugAndroidTest
	@$(ADB) -s "$(EMULATOR_DEVICE)" install -r "$(APK)" >/dev/null
	@$(ADB) -s "$(EMULATOR_DEVICE)" install -r "$(ANDROID_TEST_APK)" >/dev/null
	@$(ADB) -s "$(EMULATOR_DEVICE)" shell "cmd uimode night no"
	@$(ADB) -s "$(EMULATOR_DEVICE)" shell "run-as $(APP_ID) sh -c 'rm -f /data/data/com.airgate/files/*.park'" >/dev/null 2>&1 || true
	@$(ADB) -s "$(EMULATOR_DEVICE)" shell "am instrument -e class com.airgate.ui.ScreenshotCaptureTest com.airgate.test/androidx.test.runner.AndroidJUnitRunner" >/dev/null 2>&1 &
	@$(call wait_and_shot, pin-lock, art/screens/guide/pin-lock-light.png)
	@$(call wait_and_shot, dashboard, art/screens/screen-light.png)
	@$(call wait_and_shot, activity, art/screens/guide/activity-light.png)
	@$(call wait_and_shot, guide-violations, art/screens/guide/guide-violations-light.png)
	@$(call wait_and_shot, guide-vectors, art/screens/guide/guide-vectors-light.png)
	@$(call wait_and_shot, settings, art/screens/guide/settings-light.png)
	@$(call wait_and_shot, settings-mid, art/screens/guide/settings-mid-light.png)
	@$(call wait_and_shot, settings-scope, art/screens/guide/settings-scope-light.png)
	@$(call wait_and_shot, settings-bottom, art/screens/guide/settings-bottom-light.png)
	@$(call wait_and_shot, pin-change, art/screens/guide/pin-change-light.png)
	@$(call wait_and_shot, wipe, art/screens/guide/wipe-light.png)
	@wait

screens-dark: emulator build
	@mkdir -p art/screens/guide
	@$(GRADLE) :app:assembleDebugAndroidTest
	@$(ADB) -s "$(EMULATOR_DEVICE)" install -r "$(APK)" >/dev/null
	@$(ADB) -s "$(EMULATOR_DEVICE)" install -r "$(ANDROID_TEST_APK)" >/dev/null
	@$(ADB) -s "$(EMULATOR_DEVICE)" shell "cmd uimode night yes"
	@$(ADB) -s "$(EMULATOR_DEVICE)" shell "run-as $(APP_ID) sh -c 'rm -f /data/data/com.airgate/files/*.park'" >/dev/null 2>&1 || true
	@$(ADB) -s "$(EMULATOR_DEVICE)" shell "am instrument -e class com.airgate.ui.ScreenshotCaptureTest com.airgate.test/androidx.test.runner.AndroidJUnitRunner" >/dev/null 2>&1 &
	@$(call wait_and_shot, pin-lock, art/screens/guide/pin-lock-dark.png)
	@$(call wait_and_shot, dashboard, art/screens/screen-dark.png)
	@$(call wait_and_shot, activity, art/screens/guide/activity-dark.png)
	@$(call wait_and_shot, guide-violations, art/screens/guide/guide-violations-dark.png)
	@$(call wait_and_shot, guide-vectors, art/screens/guide/guide-vectors-dark.png)
	@$(call wait_and_shot, settings, art/screens/guide/settings-dark.png)
	@$(call wait_and_shot, settings-mid, art/screens/guide/settings-mid-dark.png)
	@$(call wait_and_shot, settings-scope, art/screens/guide/settings-scope-dark.png)
	@$(call wait_and_shot, settings-bottom, art/screens/guide/settings-bottom-dark.png)
	@$(call wait_and_shot, pin-change, art/screens/guide/pin-change-dark.png)
	@$(call wait_and_shot, wipe, art/screens/guide/wipe-dark.png)
	@wait

# Wrap every screenshot in the SVG-designed Android phone mockup
# (art/screens/mockups/pieces/*.svg) to produce the docs' final images. Regenerates
# the raw screenshots first, then runs the merge script.
mockups: screens screens-dark
	python3 tools/make-mockups.py

# Re-run only the mockup merge script from the screenshots already in art/screens/.
# Fast path for when the phone-frame styling (pieces/*.svg, tools/make-mockups.py)
# changes but the raw screenshots are unchanged.
mockups-only:
	python3 tools/make-mockups.py

logcat: emulator
	$(ADB) -s "$(EMULATOR_DEVICE)" logcat -v time | grep --line-buffered "$(APP_ID)"

emulator:
	@test -x "$(EMULATOR_SCRIPT)" || { echo "error: $(EMULATOR_SCRIPT) missing or not executable"; exit 1; }
	@$(EMULATOR_SCRIPT) "$(ADB)" "$(EMULATOR)" "$(AVD)"

emulator-start: emulator

emulator-stop:
	$(ADB) emu kill
