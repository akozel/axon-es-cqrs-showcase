"""Collects the JaCoCo HTML reports of all modules into one static site with an index page.

Usage: python3 .github/scripts/coverage_site.py <target directory>

Run from the repository root after `./gradlew check`. Every module with a report under
build/reports/jacoco/test/ is picked up. Writes <target>/index.html and <target>/<module>/ (the module's report),
and appends a summary table to $GITHUB_STEP_SUMMARY when it is set. Fails when no report exists.
"""

import html
import os
import shutil
import sys
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path

REPORT_XML = "build/reports/jacoco/test/jacocoTestReport.xml"
REPORT_HTML = "build/reports/jacoco/test/html"
COUNTERS = {"INSTRUCTION": "Instructions", "BRANCH": "Branches", "LINE": "Lines"}


def coverage(report_xml):
    """Covered ratio (or None when there is nothing to cover) of each counter, for the module as a whole."""
    root = ET.parse(report_xml).getroot()
    ratios = {name: None for name in COUNTERS}
    for counter in root.findall("counter"):  # direct children: the module's totals
        name = counter.get("type")
        if name in ratios:
            missed, covered = int(counter.get("missed")), int(counter.get("covered"))
            ratios[name] = covered / (missed + covered) if missed + covered else None
    return ratios


def percent(ratio):
    return "n/a" if ratio is None else f"{ratio * 100:.1f}%"


def index_page(modules, commit, generated):
    rows = "\n".join(
        f'<tr><td><a href="{html.escape(name)}/index.html">{html.escape(name)}</a></td>'
        + "".join(f"<td>{percent(ratios[c])}</td>" for c in COUNTERS)
        + "</tr>"
        for name, ratios in modules)
    headers = "".join(f"<th>{label}</th>" for label in COUNTERS.values())
    return f"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Test coverage</title>
<style>
  :root {{ --bg: #fff; --fg: #1f2328; --muted: #59636e; --line: #d1d9e0; --link: #0969da; }}
  @media (prefers-color-scheme: dark) {{
    :root {{ --bg: #0d1117; --fg: #e6edf3; --muted: #9198a1; --line: #3d444d; --link: #4493f8; }}
  }}
  body {{ margin: 0; padding: 32px 16px; background: var(--bg); color: var(--fg);
         font: 15px/1.5 system-ui, -apple-system, "Segoe UI", sans-serif; }}
  main {{ max-width: 760px; margin: 0 auto; }}
  h1 {{ font-size: 22px; margin: 0 0 4px; }}
  p {{ color: var(--muted); margin: 0 0 24px; }}
  .table {{ overflow-x: auto; }}
  table {{ border-collapse: collapse; width: 100%; }}
  th, td {{ padding: 8px 12px; border-bottom: 1px solid var(--line); text-align: right; white-space: nowrap; }}
  th:first-child, td:first-child {{ text-align: left; }}
  th {{ color: var(--muted); font-weight: 600; }}
  td {{ font-variant-numeric: tabular-nums; }}
  a {{ color: var(--link); }}
</style>
</head>
<body>
<main>
<h1>Test coverage</h1>
<p>Commit <code>{html.escape(commit)}</code>, {html.escape(generated)}. Minimum: 95% of instructions, branches and
lines, for each module and for each class. Open a module for its sources with covered code highlighted.</p>
<div class="table">
<table>
<thead><tr><th>Module</th>{headers}</tr></thead>
<tbody>
{rows}
</tbody>
</table>
</div>
</main>
</body>
</html>
"""


def summary(modules):
    lines = ["### Test coverage", "", "| Module | " + " | ".join(COUNTERS.values()) + " |",
             "|---|" + "---:|" * len(COUNTERS)]
    lines += [f"| {name} | " + " | ".join(percent(ratios[c]) for c in COUNTERS) + " |" for name, ratios in modules]
    return "\n".join(lines) + "\n"


def main(target):
    site = Path(target)
    shutil.rmtree(site, ignore_errors=True)
    site.mkdir(parents=True)

    modules = []
    for report_xml in sorted(Path.cwd().glob("*/" + REPORT_XML)):
        module = report_xml.relative_to(Path.cwd()).parts[0]
        shutil.copytree(Path(module) / REPORT_HTML, site / module)
        modules.append((module, coverage(report_xml)))
    if not modules:
        sys.exit("No coverage report found; run ./gradlew check first")

    commit = os.environ.get("GITHUB_SHA", "local")[:12]
    generated = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    (site / "index.html").write_text(index_page(modules, commit, generated), encoding="utf-8")

    step_summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if step_summary:
        with open(step_summary, "a", encoding="utf-8") as out:
            out.write(summary(modules))
    for name, ratios in modules:
        print(name, *(f"{COUNTERS[c]} {percent(r)}" for c, r in ratios.items()))


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
