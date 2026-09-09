import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from nasfind.config import is_mountpoint

spec = importlib.util.spec_from_file_location("container_entrypoint", Path(__file__).resolve().parents[1] / "docker/entrypoint.py")
entrypoint = importlib.util.module_from_spec(spec)
spec.loader.exec_module(entrypoint)


class ContainerSetupTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.env = {"NAS_FIND_UNC_PREFIX": r"\\nas-test\files", "NAS_FIND_ALLOWED_NETWORKS": "192.0.2.0/24"}

    def prepare(self, environ=None):
        with contextlib.redirect_stdout(io.StringIO()) as output:
            result = entrypoint.prepare(self.directory, self.env if environ is None else environ)
        self.assertNotIn((self.directory / "password").read_text().strip(), output.getvalue())
        return result

    def test_first_start_restart_and_explicit_updates_preserve_state(self):
        path = self.prepare()
        password = (self.directory / "password").read_bytes()
        self.assertGreaterEqual(len(password.strip()), 12)
        state = self.directory / "state" / "files.db"
        state.write_bytes(b"existing-index")
        config = json.loads(path.read_text())
        self.assertFalse(config["prune_bind_mounts"])
        config["exclude_paths"] = ["private"]
        config["reconcile_interval"] = 3600
        path.write_text(json.dumps(config))
        self.prepare({})
        self.assertEqual(json.loads(path.read_text())["exclude_paths"], ["private"])
        self.env["NAS_FIND_UNC_PREFIX"] = r"\\nas-test\renamed"
        self.env["NAS_FIND_ALLOWED_NETWORKS"] = "198.51.100.0/24"
        self.prepare()
        updated = json.loads(path.read_text())
        self.assertEqual(updated["unc_prefix"], self.env["NAS_FIND_UNC_PREFIX"])
        self.assertNotIn("192.0.2.0/24", updated["allowed_networks"])
        self.assertIn("127.0.0.0/8", updated["allowed_networks"])
        self.assertEqual(updated["reconcile_interval"], 3600)
        self.assertEqual((self.directory / "password").read_bytes(), password)
        self.assertEqual(state.read_bytes(), b"existing-index")

    def test_invalid_environment_does_not_replace_existing_config_or_password(self):
        path = self.prepare()
        before = path.read_bytes(), (self.directory / "password").read_bytes()
        for key, value in (("NAS_FIND_UNC_PREFIX", "bad"), ("NAS_FIND_ALLOWED_NETWORKS", ""),
                           ("NAS_FIND_ALLOWED_NETWORKS", "not-a-network")):
            with self.assertRaises(ValueError):
                entrypoint.prepare(self.directory, {**self.env, key: value})
            self.assertEqual((path.read_bytes(), (self.directory / "password").read_bytes()), before)

    def test_requires_explicit_initial_settings(self):
        for environ in ({}, {"NAS_FIND_UNC_PREFIX": r"\\nas-test\files"}, {"NAS_FIND_ALLOWED_NETWORKS": "192.0.2.0/24"}):
            with self.assertRaises(ValueError):
                entrypoint.prepare(self.directory, environ)
        self.assertFalse((self.directory / "password").exists())

    def test_invalid_existing_password_is_not_reset(self):
        (self.directory / "password").write_text("short")
        with self.assertRaisesRegex(ValueError, "12"):
            entrypoint.prepare(self.directory, self.env)
        self.assertEqual((self.directory / "password").read_text(), "short")


class BindMountTests(unittest.TestCase):
    def test_pruning_option_preserves_native_default_and_requires_boolean(self):
        from nasfind.config import normalize
        config = {"root": "/data", "unc_prefix": r"\\nas-test\files",
                  "plocate": "/usr/bin/plocate", "updatedb": "/usr/sbin/updatedb.plocate"}
        self.assertTrue(normalize(config)["prune_bind_mounts"])
        self.assertFalse(normalize({**config, "prune_bind_mounts": False})["prune_bind_mounts"])
        with self.assertRaises(ValueError):
            normalize({**config, "prune_bind_mounts": "no"})

    def test_same_device_bind_mount_and_escaped_path(self):
        table = "21 1 8:1 /storage /data rw - ext4 /dev/sda rw\n22 1 8:1 /space /data\\040space rw - ext4 /dev/sda rw\n"
        with patch("nasfind.config.os.path.ismount", return_value=False), patch("nasfind.config.Path.read_text", return_value=table):
            for name, expected in (("/data", True), ("/data space", True), ("/data/child", False)):
                with patch("nasfind.config.os.path.realpath", return_value=name):
                    self.assertEqual(is_mountpoint(name), expected)

    def test_missing_mount_table_does_not_disable_protection(self):
        with patch("nasfind.config.os.path.ismount", return_value=False), patch("nasfind.config.Path.read_text", side_effect=OSError):
            self.assertFalse(is_mountpoint("/data"))
