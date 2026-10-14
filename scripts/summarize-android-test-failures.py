#!/usr/bin/env python3
"""Expose connected-test failures in CI logs as well as downloadable reports."""
from pathlib import Path
import xml.etree.ElementTree as ET

for path in sorted(Path('.').glob('*/build/outputs/androidTest-results/**/TEST-*.xml')):
    try:
        root = ET.parse(path).getroot()
    except (ET.ParseError, OSError) as error:
        print(f'Could not read {path}: {error}')
        continue
    for case in root.iter('testcase'):
        for failure in list(case.findall('failure')) + list(case.findall('error')):
            print(f"\n{path}: {case.get('classname', '')}.{case.get('name', '')}")
            print(failure.get('message', ''))
            print((failure.text or '')[:12000])
    # Device/installation failures can occur before JUnit starts any testcase.
    for failure in list(root.findall('failure')) + list(root.findall('error')):
        print(f'\n{path}: {failure.get("message", "Device failure")}')
        print((failure.text or '')[:12000])
