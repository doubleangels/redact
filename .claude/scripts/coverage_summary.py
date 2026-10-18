"""Summarise a JaCoCo XML report: overall line and branch coverage, then classes by missed lines.

Usage: python .claude/scripts/coverage_summary.py [report.xml] [--top N]
Defaults to the AGP unit test report, app/build/reports/coverage/test/debug/report.xml.
"""
import sys
import xml.etree.ElementTree as ET

args = [a for a in sys.argv[1:] if not a.startswith("--")]
top = int(sys.argv[sys.argv.index("--top") + 1]) if "--top" in sys.argv else 30
path = args[0] if args and not args[0].isdigit() else "app/build/reports/coverage/test/debug/report.xml"
root = ET.parse(path).getroot()


def counter(node, kind):
    for c in node.findall("counter"):
        if c.get("type") == kind:
            return int(c.get("missed")), int(c.get("covered"))
    return 0, 0


def pct(missed, covered):
    total = missed + covered
    return 100.0 * covered / total if total else 100.0


for kind in ("LINE", "BRANCH", "INSTRUCTION"):
    m, c = counter(root, kind)
    print(f"{kind:<12} {pct(m, c):5.1f}%  ({c}/{m + c} covered, {m} missed)")

rows = []
for package in root.findall("package"):
    for cls in package.findall("class"):
        m, c = counter(cls, "LINE")
        if m:
            rows.append((m, c, cls.get("name").replace("/", ".")))
rows.sort(reverse=True)
print(f"\nTop {top} classes by missed lines:")
for m, c, name in rows[:top]:
    print(f"{m:6} missed  {pct(m, c):5.1f}%  {name}")

# With --lines <SourceFile.java>, list that file's uncovered line ranges (e.g. MetadataDisplayer.java).
if "--lines" in sys.argv:
    wanted = sys.argv[sys.argv.index("--lines") + 1]
    for package in root.findall("package"):
        for source in package.findall("sourcefile"):
            if source.get("name") != wanted:
                continue
            missed = [int(l.get("nr")) for l in source.findall("line") if int(l.get("ci")) == 0 and int(l.get("mi")) > 0]
            ranges, start = [], None
            for i, n in enumerate(missed):
                if start is None:
                    start = n
                if i + 1 == len(missed) or missed[i + 1] > n + 3:
                    ranges.append(f"{start}-{n}" if n != start else str(n))
                    start = None
            print(f"\n{package.get('name')}/{wanted}: {len(missed)} uncovered lines")
            print(", ".join(ranges))
