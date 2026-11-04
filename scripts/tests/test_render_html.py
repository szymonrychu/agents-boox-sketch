import json
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

PAGE = Path(__file__).resolve().parents[2] / "app/src/main/assets/mermaid/render.html"

HARNESS = """
const calls = [];
global.window = {};
global.Android = { done: (h) => calls.push(["done", h]), fail: (k, m) => calls.push(["fail", k, m]) };
global.mermaid = { initialize() {}, render: async () => ({ svg: "<svg></svg>" }) };
global.document = {
  getElementById: () => ({ set innerHTML(v) {} }),
  fonts: { ready: Promise.resolve() },
  body: { getBoundingClientRect: () => ({ height: 120.4 }) },
};
global.requestAnimationFrame = () => {}; // an unattached web view: frames never run
eval(SCRIPT);
renderDiagram("graph TD; A-->B");
setTimeout(() => { console.log(JSON.stringify(calls)); process.exit(0); }, 1500);
"""


@unittest.skipUnless(shutil.which("node"), "node is not installed")
class RenderHtmlTest(unittest.TestCase):
    def test_done_is_signalled_when_animation_frames_never_run(self):
        html = PAGE.read_text()
        script = html.split("<script>")[1].split("</script>")[0]
        with tempfile.TemporaryDirectory() as tmp:
            runner = Path(tmp) / "run.js"
            runner.write_text("const SCRIPT = " + json.dumps(script) + ";\n" + HARNESS)
            out = subprocess.run(["node", str(runner)], capture_output=True, text=True, timeout=30, check=True)
        self.assertEqual(json.loads(out.stdout), [["done", 121]])


if __name__ == "__main__":
    unittest.main()
