#!/usr/bin/env python3
"""Prints android.json — what GET /app/android of the CRM answers (docs/ANDROID_CONTRACT.md §2.1), minus `url`.

Usage: android_json.py <output-metadata.json> <apk> <app/build.gradle.kts>
"""
import hashlib
import json
import os
import re
import sys

meta_path, apk, gradle = sys.argv[1:4]
meta = json.load(open(meta_path))["elements"][0]
contract = re.search(r'buildConfigField\("String", "CONTRACT", "\\"([0-9.]+)\\""\)', open(gradle).read()).group(1)
data = open(apk, "rb").read()
print(json.dumps({
    "contract": contract,
    "version_code": meta["versionCode"],
    "version_name": meta["versionName"],
    "sha256": hashlib.sha256(data).hexdigest(),
    "size": len(data),
}, ensure_ascii=False, indent=2))
