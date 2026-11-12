# AshCRM для Android. Сборка — локально, JDK из Android Studio (README.md).
JAVA_HOME ?= $(HOME)/Applications/android-studio/jbr
export JAVA_HOME
export PATH := $(JAVA_HOME)/bin:$(PATH)
SDK      ?= $(HOME)/Android/Sdk
ADB      := $(SDK)/platform-tools/adb
GRADLE   := ./gradlew --console=plain
DIST     := dist

.PHONY: debug release test lint check install install-release dist publish emulator clean

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

VERSION = $$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)

dist: check release  ## ashcrm.apk + android.json для GitHub (приложения до 0.4.x)
	@mkdir -p $(DIST)/github
	cp app/build/outputs/apk/release/app-release.apk $(DIST)/ashcrm.apk
	python3 -I scripts/android_json.py app/build/outputs/apk/release/output-metadata.json $(DIST)/ashcrm.apk app/build.gradle.kts release \
		"https://github.com/AshByteDev/crm-ashbyte-android/releases/download/v$(VERSION)/ashcrm.apk" > $(DIST)/github/android.json
	@cat $(DIST)/github/android.json; echo

# Выпуск — сразу в два места (решение владельца 12.11: GitHub дублируется несколько версий, потом отключается):
#  * GitHub: ashcrm.apk + android.json — для телефонов до 0.4.x, они проверяют только GitHub;
#  * GitVerse: релиз с ashcrm.apk; release/android.json в ветке main указывает на него — его читают телефоны с 0.5.0.
# Собирается и подписывается здесь: ключ подписи не покидает компьютер. Токен GitVerse — ~/.android-keys/gitverse.token.
publish: dist
	@test -z "$$(git status --porcelain)" || { echo "есть незакоммиченные изменения — сначала коммит"; exit 1; }
	@git fetch -q origin
	@test "$$(git rev-parse HEAD)" = "$$(git rev-parse @{u})" || { echo "ветка не совпадает с GitHub — сначала git push"; exit 1; }
	@V=$(VERSION); \
	if gh release view "v$$V" >/dev/null 2>&1; then echo "релиз v$$V уже есть — поднимите versionCode/versionName"; exit 1; fi; \
	git tag "v$$V" && git push -q gitverse main "v$$V" && \
	gh release create "v$$V" $(DIST)/ashcrm.apk $(DIST)/github/android.json --title "AshCRM $$V" --notes-file release/notes.txt --target "$$(git rev-parse HEAD)" && \
	URL=$$(python3 -I scripts/gitverse_release.py "v$$V" $(DIST)/ashcrm.apk release/notes.txt) && \
	python3 -I scripts/android_json.py app/build/outputs/apk/release/output-metadata.json $(DIST)/ashcrm.apk app/build.gradle.kts release "$$URL" > release/android.json && \
	git add release/android.json && git commit -q -m "Выпуск $$V: release/android.json → GitVerse" && \
	git push -q origin main && git push -q gitverse main && \
	echo "опубликовано на GitHub и GitVerse: телефоны предложат обновление при следующем запуске"
	@$(GRADLE) --stop -q  # отдать память: Gradle и компилятор Kotlin держат по нескольку ГБ

emulator:         ## эмулятор без окна (Medium_Phone_API_37.0 из Android Studio)
	$(SDK)/emulator/emulator -avd Medium_Phone_API_37.0 -no-window -no-audio -no-boot-anim -no-snapshot-save &

clean:
	$(GRADLE) clean
	rm -rf $(DIST)
