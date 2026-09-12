import contextlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch, Mock

import deploy
from scripts import deploy_remote as remote


def profile(**extra):
    return {"root":"/srv/fixture", "unc_prefix":"\\\\nas-test\\files", "plocate":sys.executable, "updatedb":sys.executable, **extra}


class DeploymentInputs(unittest.TestCase):
    def test_no_implicit_host_or_configuration(self):
        for argv in ([], ["--host","nas"], ["--config","config.json"], ["--host","nas;echo bad","--config","config.json"]):
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit): deploy.parse_args(argv)
        value = deploy.parse_args(["--host","user@nas-test","--config","config.json","--check"])
        self.assertTrue(value.check)
        self.assertFalse(value.update_config or value.enable_service or value.linger)

    def test_invalid_profile_does_not_contact_ssh(self):
        with tempfile.TemporaryDirectory() as folder, patch("deploy.subprocess.check_output") as ssh:
            path = Path(folder)/"profile.json"; path.write_text("{}")
            with self.assertRaises(ValueError): deploy.main(["--host","nas-test","--config",str(path)])
            ssh.assert_not_called()

    def test_packaging_excludes_private_data_and_includes_templates_and_license(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder)/"source";root.mkdir()
            for name in ("nasfind/__init__.py","scripts/deploy_remote.py","examples/server-config.json","docker/entrypoint.py","LICENSE","AGENTS.md", ".local/access.json","desktop/private.txt","nasfind/__pycache__/cached.pyc"):
                path=root/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text("fixture")
            package=Path(folder)/"source.tar.gz";deploy.package(package,root)
            with tarfile.open(package) as archive: names=set(archive.getnames())
            self.assertIn("LICENSE",names);self.assertIn("examples/server-config.json",names)
            self.assertIn("scripts/deploy_remote.py",names)
            self.assertIn("docker/entrypoint.py",names)
            self.assertFalse(any(".local" in p or "desktop" in p or "__pycache__" in p for p in names))

    def test_public_url_is_explicit_for_wildcard_binds(self):
        with self.assertRaises(ValueError): remote.service_url({"bind":"0.0.0.0","port":8765})
        self.assertEqual(remote.service_url({},"https://nas.example.internal/"),"https://nas.example.internal")
        with self.assertRaises(ValueError): remote.service_url({},"http://user:secret@host")
        with self.assertRaises(ValueError): remote.service_url({},"http://host:invalid")

    def test_missing_or_wrong_dependencies_fail_without_installing_packages(self):
        with tempfile.TemporaryDirectory() as root:
            config=profile(root=root,require_mount=False)
            with patch.object(remote,"command",side_effect=FileNotFoundError),self.assertRaisesRegex(ValueError,"准备依赖"):
                remote.preflight(config)
            with patch.object(remote,"command",return_value=SimpleNamespace(stdout="mlocate 1.0")),self.assertRaisesRegex(ValueError,"不是 plocate"):
                remote.preflight(config)


class DeploymentMigration(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name)
        self.current=self.root/"current";self.conf=self.root/"conf/config.json"
        self.conf.parent.mkdir();self.conf.write_text('{"original": true}')
        self.old=profile(exclude_paths=["cache/private"],allowed_networks=["192.0.2.0/24"],
                         state_dir=str(self.root/"state"),password_file=str(self.root/"password"))
    def tearDown(self): self.temp.cleanup()

    def test_existing_effective_configuration_is_preserved_by_default(self):
        before=self.conf.read_bytes()
        with patch.object(remote,"previous_config",return_value=self.old):
            value,source=remote.configuration(self.current,self.conf,profile(unc_prefix="\\\\other\\share"))
        self.assertEqual(source,"preserved")
        self.assertEqual(value["unc_prefix"],self.old["unc_prefix"])
        self.assertEqual(value["exclude_paths"],["cache/private"])
        self.assertEqual(value["allowed_networks"],["192.0.2.0/24"])
        self.assertEqual(self.conf.read_bytes(),before)

    def test_explicit_config_update_and_durable_location_guard(self):
        with patch.object(remote,"previous_config",return_value=self.old):
            value,source=remote.configuration(self.current,self.conf,{"exclude_paths":[]},True)
            self.assertEqual(source,"updated");self.assertEqual(value["exclude_paths"],[])
            with self.assertRaisesRegex(ValueError,"password_file"):
                remote.configuration(self.current,self.conf,{"password_file":str(self.root/"different")},True)

    def test_installed_loader_supplies_legacy_defaults(self):
        module=self.current/"nasfind";module.mkdir(parents=True)
        (module/"__init__.py").touch()
        (module/"config.py").write_text("def load(path):\n    return "+repr(self.old)+"\n")
        self.assertEqual(remote.previous_config(self.current,self.conf),self.old)

    def test_unreadable_legacy_defaults_require_an_explicit_update(self):
        with patch.object(remote,"previous_config",side_effect=ValueError("old loader failed")):
            with self.assertRaisesRegex(ValueError,"update-config"):
                remote.configuration(self.current,self.conf,profile())
            value,source=remote.configuration(self.current,self.conf,profile(),True)
            self.assertEqual(source,"updated")
            self.assertEqual(value["unc_prefix"],profile()["unc_prefix"])

    def test_check_mode_does_not_create_a_deployment(self):
        home=self.root/"empty-home";home.mkdir()
        with patch.object(Path,"home",return_value=home),patch.object(remote,"preflight"):
            result=remote.run(self.root/"staged",{"config":profile(),"check":True})
        self.assertTrue(result["checked"])
        self.assertEqual(list(home.iterdir()),[])


class ActivationTests(unittest.TestCase):
    def transaction(self, existing, fail):
        with tempfile.TemporaryDirectory() as directory:
            base=Path(directory);conf=base/"config.json";unit=base/"service";current=base/"current"
            original=b'{"previous":true}'
            if existing:conf.write_bytes(original);unit.write_text("old unit")
            state={"target":"old" if existing else None}
            def switch(path,target):state["target"]=target
            health=Mock(side_effect=RuntimeError("bad health") if fail else None)
            with patch.object(Path,"is_symlink",lambda path: path==current and existing),patch.object(remote.os,"readlink",return_value="old"),patch.object(remote,"switch",side_effect=switch),patch.object(remote,"command",return_value=SimpleNamespace(stdout="")) as command:
                if fail:
                    with self.assertRaisesRegex(RuntimeError,"已恢复"):
                        remote.activate(base,conf,base/"new",{"new":True},unit,"new unit",health)
                    self.assertEqual(state["target"],"old" if existing else None)
                    if existing:
                        self.assertEqual(conf.read_bytes(),original);self.assertEqual(unit.read_text(),"old unit")
                        self.assertTrue(list((base/"backups").glob("*.json")))
                    else:
                        self.assertFalse(conf.exists() or unit.exists())
                else:
                    remote.activate(base,conf,base/"new",{"new":True},unit,"new unit",health)
                    self.assertEqual(json.loads(conf.read_text()),{"new":True})
                    self.assertEqual(state["target"],base/"new")
                    health.assert_called_once()

    def test_failed_upgrade_restores_config_unit_and_link(self):self.transaction(True,True)
    def test_failed_fresh_install_does_not_leave_active_config(self):self.transaction(False,True)
    def test_success_publishes_after_health_check(self):self.transaction(True,False)
