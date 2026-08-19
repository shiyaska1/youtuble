#!/usr/bin/env python3
"""Generate a ytsaver activation key for a given Device ID.

Must stay in sync with LicenseManager.SECRET_SALT and
LicenseManager.expectedKeyFor() in the Kotlin app - if you change the salt
here, change it there too (and vice versa), or previously issued keys will
stop matching.

Usage:
    python3 tools/generate_key.py <DEVICE_ID>
"""
import hashlib
import hmac
import sys

SECRET_SALT = "POSB-change-this-secret-2024"


def expected_key_for(device_id: str) -> str:
    digest = hmac.new(
        SECRET_SALT.encode("utf-8"), device_id.encode("utf-8"), hashlib.sha256
    ).hexdigest().upper()
    hex16 = digest[:16]
    return "-".join(hex16[i:i + 4] for i in range(0, 16, 4))


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("Usage: python3 tools/generate_key.py <DEVICE_ID>")
        sys.exit(1)
    print(expected_key_for(sys.argv[1]))
