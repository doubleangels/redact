import sys

def print_lines(filename, lines):
    with open(filename, 'r', encoding='utf-8') as f:
        file_lines = f.readlines()
    for l in lines:
        if l-1 < len(file_lines):
            print(f"{l}: {file_lines[l-1].strip()}")

print_lines('C:/Users/mattv/AndroidStudioProjects/redact/app/src/main/java/com/doubleangels/redact/metadata/MetadataStripper.java', [836,874,889,914,927,944,946,1127,1435,1595,1706,1707,1708])
