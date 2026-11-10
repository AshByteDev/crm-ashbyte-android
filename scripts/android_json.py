#!/usr/bin/env python3
"""Prints android.json of a release: what the app reads from GitHub (README → «Выпуск версии»).

Usage: android_json.py <output-metadata.json> <apk> <app/build.gradle.kts> <release dir>
"""
import hashlib
import json
import re
import sys

meta_path, apk, gradle, release_dir = sys.argv[1:5]
meta = json.load(open(meta_path))["elements"][0]
src = open(gradle).read()
contract = re.search(r'buildConfigField\("String", "CONTRACT", "\\"([0-9.]+)\\""\)', src).group(1)
prefix = re.search(r'buildConfigField\("String", "RELEASES_PREFIX", "\\"([^"\\]+)\\""\)', src).group(1)
min_code = int(open(f"{release_dir}/min_version_code").read().strip())
notes = [line.strip() for line in open(f"{release_dir}/notes.txt", encoding="utf-8") if line.strip()]
if min_code > meta["versionCode"]:
    sys.exit(f"min_version_code {min_code} > version_code {meta['versionCode']}")
if len(notes) > 5:
    sys.exit("notes.txt: no more than 5 lines, the dialog shows 5")
data = open(apk, "rb").read()
print(json.dumps({
    "version_code": meta["versionCode"],
    "version_name": meta["versionName"],
    "min_version_code": min_code,
    "contract": contract,
    "sha256": hashlib.sha256(data).hexdigest(),
    "size": len(data),
    "url": f"{prefix}v{meta['versionName']}/ashcrm.apk",
    "notes": notes,
}, ensure_ascii=False, indent=2))
