#!/usr/bin/env python3
"""Creates a release on GitVerse with ashcrm.apk and prints the address of the APK (README → «Выпуск версии»).

Usage: gitverse_release.py <tag> <apk> <notes.txt>
The token (repositories: read + write) — ~/.android-keys/gitverse.token, never in the repository.
GitVerse takes no .json as a file of a release: android.json lives in the repository (release/android.json).
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid

REPO = "AshByte/crm-ashbyte-android"
API = f"https://api.gitverse.ru/repos/{REPO}"
ACCEPT = "application/vnd.gitverse.object+json;version=1"

tag, apk, notes = sys.argv[1:4]
token = open(os.path.expanduser("~/.android-keys/gitverse.token")).read().strip()


def call(method, url, body=None, content_type=None):
    headers = {"Authorization": f"Bearer {token}", "Accept": ACCEPT}
    if content_type:
        headers["Content-Type"] = content_type
    for wait in (5, 15, 30, None):
        req = urllib.request.Request(url, data=body, method=method, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=300) as res:
                return res.status, json.loads(res.read() or b"null")
        except urllib.error.HTTPError as e:
            if e.code == 429 and wait:
                time.sleep(wait)
                continue
            return e.code, json.loads(e.read() or b"null")


status, _ = call("GET", f"{API}/releases/tags/{tag}")
if status == 200:
    sys.exit(f"GitVerse: релиз {tag} уже есть — поднимите versionCode/versionName")

status, rel = call("POST", f"{API}/releases", json.dumps({
    "tag_name": tag,
    "name": f"AshCRM {tag.lstrip('v')}",
    "body": open(notes, encoding="utf-8").read(),
    "draft": False,
    "prerelease": False,
}).encode(), "application/json")
if status != 201:
    sys.exit(f"GitVerse: релиз не создан: {status} {rel}")

boundary = uuid.uuid4().hex
data = open(apk, "rb").read()
body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"attachment\"; filename=\"ashcrm.apk\"\r\n"
        f"Content-Type: application/vnd.android.package-archive\r\n\r\n").encode() + data + f"\r\n--{boundary}--\r\n".encode()
status, asset = call("POST", f"{API}/releases/{rel['id']}/assets?name=ashcrm.apk", body, f"multipart/form-data; boundary={boundary}")
if status != 201:
    sys.exit(f"GitVerse: APK не загружен: {status} {asset}")
print(asset["browser_download_url"])
