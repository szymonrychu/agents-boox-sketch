"""Print the Android versionCode for X.Y.Z: X*1000000 + Y*1000 + Z.

Usage: python3 scripts/version_code.py X.Y.Z. Exits 2 on a malformed version or a
minor or patch component above 999. Stdlib only.
"""

import re
import sys


def main(argv: list[str]) -> int:
    if len(argv) != 2 or not (m := re.fullmatch(r"(\d+)\.(\d+)\.(\d+)", argv[1])):
        sys.stderr.write("usage: version_code.py X.Y.Z\n")
        return 2
    major, minor, patch = (int(g) for g in m.groups())
    if minor > 999 or patch > 999:
        sys.stderr.write("minor and patch must be at most 999\n")
        return 2
    print(major * 1000000 + minor * 1000 + patch)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
