import xml.etree.ElementTree as ET

tree = ET.parse(r"c:\Users\mattv\AndroidStudioProjects\redact\app\build\reports\jacoco\jacocoTestReport\jacocoTestReport.xml")
root = tree.getroot()

for package in root.findall(".//package"):
    for sourcefile in package.findall(".//sourcefile"):
        for line in sourcefile.findall(".//line"):
            mi = int(line.get("mi", 0))
            ci = int(line.get("ci", 0))
            if mi > 0 and ci == 0:
                print(f"MISSING LINE: {package.get('name')}/{sourcefile.get('name')} Line: {line.get('nr')} missed instructions: {mi}")
