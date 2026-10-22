from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET


class LocalizationResourcesTests(unittest.TestCase):
    def test_korean_resources_cover_english_and_preserve_format_arguments(self):
        resources = Path(__file__).resolve().parents[2] / 'android/app/src/main/res'
        english = {item.attrib['name']: item for item in ET.parse(resources / 'values/strings.xml').getroot()}
        korean = {item.attrib['name']: item for item in ET.parse(resources / 'values-ko/strings.xml').getroot()}
        self.assertEqual(english.keys(), korean.keys())
        for name, item in english.items():
            with self.subTest(name=name):
                self.assertEqual(item.tag, korean[name].tag)
                if item.tag == 'string':
                    expected = sorted(re.findall(r'%\d+\$[dsf]', item.text or ''))
                    self.assertEqual(expected, sorted(re.findall(r'%\d+\$[dsf]', korean[name].text or '')))
                    self.assertTrue((korean[name].text or '').strip())
                else:
                    quantities = {child.attrib['quantity']: child.text for child in item}
                    self.assertIn('one', quantities)
                    self.assertIn('other', quantities)
                    self.assertTrue(any(child.attrib['quantity'] == 'other' for child in korean[name]))
