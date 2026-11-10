# AshCRM для Android. Сборка — локально, JDK из Android Studio (README.md).
JAVA_HOME ?= $(HOME)/Applications/android-studio/jbr
export JAVA_HOME
export PATH := $(JAVA_HOME)/bin:$(PATH)
SDK      ?= $(HOME)/Android/Sdk
ADB      := $(SDK)/platform-tools/adb
GRADLE   := ./gradlew --console=plain
DIST     := dist

.PHONY: debug release test lint check install install-release dist emulator clean

debug:            ## APK для отладки (пакет ru.ashcrm.app.debug, ставится рядом с боевым)
	$(GRADLE) assembleDebug

release:          ## подписанный APK (нужен keystore.properties)
	@test -f keystore.properties || { echo "нет keystore.properties — см. README, раздел «Ключ подписи»"; exit 1; }
	$(GRADLE) assembleRelease

test:             ## юнит-тесты: шифрование по RFC 8291, правила версий, адрес портала
	$(GRADLE) testDebugUnitTest

lint:
	$(GRADLE) lintDebug lintRelease

check: test lint  ## всё, что должно быть зелёным перед выпуском

install: debug    ## поставить debug на подключённый телефон или эмулятор
	$(ADB) install -r app/build/outputs/apk/debug/app-debug.apk

install-release: release
	$(ADB) install -r app/build/outputs/apk/release/app-release.apk

dist: check release  ## файлы выпуска: ashcrm.apk + android.json
	@mkdir -p $(DIST)
	cp app/build/outputs/apk/release/app-release.apk $(DIST)/ashcrm.apk
	python3 scripts/android_json.py app/build/outputs/apk/release/output-metadata.json $(DIST)/ashcrm.apk app/build.gradle.kts > $(DIST)/android.json
	@cat $(DIST)/android.json; echo

emulator:         ## эмулятор без окна (Medium_Phone_API_37.0 из Android Studio)
	$(SDK)/emulator/emulator -avd Medium_Phone_API_37.0 -no-window -no-audio -no-boot-anim -no-snapshot-save &

clean:
	$(GRADLE) clean
	rm -rf $(DIST)
