"""Print a markdown bullet list of commit subjects since a tag.

Usage: python3 scripts/release_notes.py <prev_tag|"">. An empty tag lists every commit
reachable from HEAD. Run from the repository work tree. Stdlib only.
"""

import subprocess
import sys


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        sys.stderr.write('usage: release_notes.py <prev_tag|"">\n')
        return 2
    rng = f"{argv[1]}..HEAD" if argv[1] else "HEAD"
    proc = subprocess.run(
        ["git", "log", "--format=%s", rng], capture_output=True, text=True
    )
    if proc.returncode != 0:
        sys.stderr.write(proc.stderr)
        return proc.returncode
    for subject in proc.stdout.splitlines():
        print(f"- {subject}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
