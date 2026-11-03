import os
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "public_check.sh"
GOOD = ("szymonrychu", "1+szymonrychu@users.noreply.github.com")


def git(repo, *args, env=None):
    full = dict(os.environ)
    full.update(env or {})
    return subprocess.run(
        ["git", *args], cwd=repo, env=full, check=True, capture_output=True, text=True
    )


def commit(repo, path, content, author=GOOD, committer=None, message="chore: add"):
    committer = committer or author
    target = Path(repo) / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content)
    git(repo, "add", "-A")
    git(
        repo,
        "commit",
        "-q",
        "-m",
        message,
        env={
            "GIT_AUTHOR_NAME": author[0],
            "GIT_AUTHOR_EMAIL": author[1],
            "GIT_COMMITTER_NAME": committer[0],
            "GIT_COMMITTER_EMAIL": committer[1],
        },
    )


class PublicCheckTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.repo = self._tmp.name
        git(self.repo, "init", "-q", "-b", "main")
        git(self.repo, "config", "commit.gpgsign", "false")

    def run_check(self, denylist=None):
        env = dict(os.environ)
        env.pop("PUBLIC_DENYLIST", None)
        if denylist is not None:
            env["PUBLIC_DENYLIST"] = denylist
        return subprocess.run(
            ["bash", str(SCRIPT)], cwd=self.repo, env=env, capture_output=True, text=True
        )

    def test_public_check_clean_repo_passes(self):
        commit(self.repo, "README.md", "hello\n")
        result = self.run_check()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_public_check_flags_planted_secret(self):
        commit(self.repo, "key.pem", "-----BEGIN RSA PRIVATE KEY-----\nabc\n")
        git(self.repo, "rm", "-q", "key.pem")
        git(
            self.repo,
            "commit",
            "-q",
            "-m",
            "chore: drop",
            env={
                "GIT_AUTHOR_NAME": GOOD[0],
                "GIT_AUTHOR_EMAIL": GOOD[1],
                "GIT_COMMITTER_NAME": GOOD[0],
                "GIT_COMMITTER_EMAIL": GOOD[1],
            },
        )
        result = self.run_check()
        self.assertNotEqual(result.returncode, 0)

    def test_public_check_flags_personal_email(self):
        commit(self.repo, "a.txt", "x\n", author=("someone", "someone@example.org"))
        result = self.run_check()
        self.assertNotEqual(result.returncode, 0)

    def test_public_check_allows_github_merge_committer(self):
        commit(
            self.repo,
            "a.txt",
            "x\n",
            committer=("GitHub", "noreply@github.com"),
        )
        result = self.run_check()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_public_check_allows_bot_author(self):
        commit(
            self.repo,
            "a.txt",
            "x\n",
            author=("dependabot[bot]", "49699333+dependabot[bot]@users.noreply.github.com"),
        )
        result = self.run_check()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_public_check_ignores_version_numbers(self):
        commit(self.repo, "versions.txt", "2.10.1\n")
        result = self.run_check()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_public_check_denylist_reports_index_only(self):
        commit(self.repo, "a.txt", "internal-thing-xyz\n")
        result = self.run_check(denylist="internal-thing-xyz\n")
        out = result.stdout + result.stderr
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("denylist pattern 1", out)
        self.assertNotIn("internal-thing-xyz", out)


if __name__ == "__main__":
    unittest.main()
