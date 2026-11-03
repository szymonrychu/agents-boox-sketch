import hashlib
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parents[1]
NEXT_VERSION_SHA256 = "ebe7f7a302fe15396fbc63fe222c9bd09d2d0a2bbeb7441810c90d60f57043e7"
IDENTITY = {
    "GIT_AUTHOR_NAME": "t",
    "GIT_AUTHOR_EMAIL": "t@example.invalid",
    "GIT_COMMITTER_NAME": "t",
    "GIT_COMMITTER_EMAIL": "t@example.invalid",
}


def run(script, *args, cwd=None):
    return subprocess.run(
        [sys.executable, str(SCRIPTS / script), *args],
        cwd=cwd,
        capture_output=True,
        text=True,
    )


def git(repo, *args):
    env = dict(os.environ)
    env.update(IDENTITY)
    return subprocess.run(
        ["git", *args], cwd=repo, env=env, check=True, capture_output=True, text=True
    )


class VersionCodeTest(unittest.TestCase):
    def test_version_code(self):
        self.assertEqual(run("version_code.py", "1.2.3").stdout.strip(), "1002003")
        self.assertEqual(run("version_code.py", "0.1.0").stdout.strip(), "1000")
        self.assertEqual(run("version_code.py", "1.2").returncode, 2)

    def test_version_code_rejects_component_over_999(self):
        self.assertEqual(run("version_code.py", "1.1000.0").returncode, 2)
        self.assertEqual(run("version_code.py", "1.0.1000").returncode, 2)


class ReleaseNotesTest(unittest.TestCase):
    def test_release_notes_lists_subjects(self):
        with tempfile.TemporaryDirectory() as repo:
            git(repo, "init", "-q")
            git(repo, "commit", "-q", "--allow-empty", "-m", "chore: first")
            git(repo, "tag", "v0.1.0")
            git(repo, "commit", "-q", "--allow-empty", "-m", "feat: second")
            git(repo, "commit", "-q", "--allow-empty", "-m", "fix: third")
            out = run("release_notes.py", "v0.1.0", cwd=repo).stdout
        lines = out.splitlines()
        self.assertIn("- feat: second", lines)
        self.assertIn("- fix: third", lines)
        self.assertNotIn("first", out)

    def test_release_notes_without_tag_lists_all(self):
        with tempfile.TemporaryDirectory() as repo:
            git(repo, "init", "-q")
            git(repo, "commit", "-q", "--allow-empty", "-m", "chore: first")
            out = run("release_notes.py", "", cwd=repo).stdout
        self.assertIn("- chore: first", out.splitlines())


class NextVersionTest(unittest.TestCase):
    def test_next_version_identical_to_connector(self):
        digest = hashlib.sha256((SCRIPTS / "next_version.py").read_bytes()).hexdigest()
        self.assertEqual(digest, NEXT_VERSION_SHA256)


if __name__ == "__main__":
    unittest.main()
