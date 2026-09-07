import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

from nasfind.config import normalize, load, validate


def profile(**values):
    return {"root":"/srv/fixture", "unc_prefix":"\\\\nas-test\\files", "plocate":sys.executable, "updatedb":sys.executable, **values}


class ConfigurationTests(unittest.TestCase):
    def test_environment_fields_are_required(self):
        for key in ("root", "unc_prefix"):
            value = profile(); value.pop(key)
            with self.assertRaisesRegex(ValueError, key): normalize(value)
        with self.assertRaises(ValueError): validate(profile(root="relative/path"))

    def test_generic_defaults_and_explicit_values(self):
        value = normalize(profile())
        self.assertEqual(value["exclude_paths"], [])
        self.assertEqual(value["allowed_networks"], ["127.0.0.0/8", "::1/128"])
        self.assertTrue(str(value["state_dir"]).startswith(str(Path.home())))
        custom = normalize(profile(exclude_paths=["cache"],allowed_networks=["192.0.2.0/24"]))
        self.assertEqual(custom["exclude_paths"], ["cache"])
        self.assertEqual(custom["allowed_networks"], ["192.0.2.0/24"])

    def test_invalid_settings_fail_early(self):
        for extra in ({"port":0},{"port":True},{"allowed_networks":[]},{"exclude_paths":"cache"},
                      {"update_interval":0},{"update_interval":float("nan")},{"allowed_networks":[1]},
                      {"require_mount":"yes"},{"unc_prefix":"C:\\data"}):
            with self.assertRaises(ValueError): normalize(profile(**extra))

    def test_program_discovery_and_missing_dependency(self):
        with patch("nasfind.config.shutil.which", side_effect=lambda name: "/tools/" + name if name in ("plocate","updatedb") else None):
            value = normalize({"root":"/srv/fixture","unc_prefix":"\\\\nas-test\\files"})
        self.assertEqual(value["updatedb"], "/tools/updatedb")
        with patch("nasfind.config.shutil.which", return_value=None), self.assertRaisesRegex(ValueError,"未找到"):
            normalize({"root":"/srv/fixture","unc_prefix":"\\\\nas-test\\files"})

    def test_config_accepts_utf8_bom(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)/"config.json"
            path.write_text(json.dumps(profile()),encoding="utf-8-sig")
            self.assertEqual(load(path)["unc_prefix"], "\\\\nas-test\\files")
