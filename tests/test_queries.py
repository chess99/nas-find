import json
import csv
import io
import os
import subprocess
import tempfile
import threading
import time
import unittest
from pathlib import Path
from types import SimpleNamespace

from nasfind.config import Scope
from nasfind.queries import Queries, selection_ranges


class Selections(unittest.TestCase):
    def test_ranges_and_complements_do_not_expand_all(self):
        self.assertEqual(selection_ranges({"all": True, "ranges": [[20, 30], [10, 25]]}, 4000000), [(0, 10), (30, 4000000)])
        self.assertEqual(selection_ranges({"all": False, "ranges": [[500, 1000], [999, 2000]]}, 5000), [(500, 2000)])
        for data in ({"all": 1}, {"all": True, "ranges": [[0, 4000001]]}, {"all": False, "ranges": [[3, 2]]}):
            with self.assertRaises(ValueError): selection_ranges(data, 4000000)


@unittest.skipUnless(os.environ.get("PLOCATE_BIN"), "Requires real Linux plocate")
class QuerySnapshots(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="nas-find-query-test-")
        cls.base = Path(cls.temp.name)
        cls.root = cls.base / "data"
        cls.root.mkdir()
        for folder in ("a", "z", "旅行", "other", "目录.mp4"):
            (cls.root / folder).mkdir()
        # More than the old 10,001-candidate ceiling, with matches at the end.
        for n in range(10050): (cls.root / "a" / f"needle-{n:05}.txt").touch()
        for n in range(620): (cls.root / "z" / f"needle-{n:05}.MP4").touch()
        for name in ("旅行/片段.mp4", "旅行/说明.txt", "other/旅行日记.MKV", "other/旅行配乐.mp3"):
            (cls.root / name).touch()
        for name in ("special\nname.txt", "special\\name.txt"):
            (cls.root / name).touch()
        state = cls.base / "state"; state.mkdir()
        cls.config = {"root": str(cls.root), "require_mount": False, "exclude_names": ["node_modules"], "exclude_paths": [],
                      "plocate": os.environ["PLOCATE_BIN"], "unc_prefix": "\\\\nas\\share"}
        cls.engine = SimpleNamespace(state=state, database=state / "files.db", config=cls.config, scope=Scope(cls.config),
                                     lock=threading.RLock(), index_dirs={"a", "z", "旅行", "other", "目录.mp4"}, metadata={"finished_at": 1})
        cls.update(cls.engine.database)

    @classmethod
    def update(cls, database):
        subprocess.run([os.environ["UPDATEDB_BIN"], "-U", str(cls.root), "-o", str(database), "-l", "0",
                        "--prune-bind-mounts", "no", "--prunefs", "", "--prunepaths", "", "--prunenames", ""], check=True, capture_output=True)

    @classmethod
    def tearDownClass(cls): cls.temp.cleanup()

    def setUp(self): self.queries = Queries(self.engine)
    def tearDown(self): self.queries.close()

    def query(self, **options):
        info = self.queries.create("owner", options)
        job = self.queries.get("owner", info["id"])
        self.assertTrue(job.finished.wait(20))
        self.assertIsNone(job.error)
        self.assertTrue(job.complete)
        return job.id

    def test_types_basename_scope_and_no_candidate_truncation(self):
        key = self.query(query="needle", category="video")
        first = self.queries.page("owner", key)
        self.assertEqual(first["total"], 620)
        self.assertEqual(len(first["results"]), 500)
        last = self.queries.page("owner", key, 500)
        self.assertEqual(len(last["results"]), 120)
        self.assertTrue(all(x["path"].startswith("z/") for x in last["results"]))
        key = self.query(query="旅行")
        names = {x["path"] for x in self.queries.page("owner", key)["results"]}
        self.assertEqual(names, {"旅行", "other/旅行日记.MKV", "other/旅行配乐.mp3"})
        key = self.query(query="旅行", match_path=True, category="video")
        self.assertEqual(self.queries.page("owner", key)["total"], 2)
        key = self.query(query="旅行", scope="other", extension="mkv", category="video")
        self.assertEqual(self.queries.page("owner", key)["total"], 1)
        key = self.query(category="folder")
        self.assertEqual(self.queries.page("owner", key)["total"], 5)
        key = self.query(query=str(self.root), match_path=True)
        self.assertEqual(self.queries.page("owner", key)["total"], 0)

    def test_cross_page_selection_export_and_owner_isolation(self):
        key = self.query(query="needle", category="video")
        select = {"all": True, "ranges": [[5, 10], [510, 530]]}
        cursor, paths = 0, []
        while True:
            batch = self.queries.selected("owner", {"id": key, "selection": select, "cursor": cursor})
            self.assertEqual(batch["selected_total"], 595)
            paths.extend(batch["paths"])
            if batch["done"]: break
            self.assertGreater(batch["next_cursor"], cursor)
            cursor = batch["next_cursor"]
        self.assertEqual(len(paths), 595)
        self.assertEqual(len(set(paths)), 595)
        self.assertNotIn("z/needle-00520.MP4", paths)
        export = self.queries.prepare_export("owner", {"id": key, "selection": select, "quoted": True})
        token = export["url"].split("token=")[1]
        with self.queries.download("owner", token) as stream: data = stream.read().decode("utf-8").splitlines()
        self.assertEqual(data, ['"\\\\nas\\share\\' + p.replace('/', '\\') + '"' for p in paths])
        with self.assertRaises(ValueError): self.queries.page("other-owner", key)
        with self.assertRaises(ValueError): self.queries.download("other-owner", token)
        self.queries.get("owner", key).complete = False
        with self.assertRaises(ValueError): self.queries.selected("owner", {"id": key, "selection": select})

    def test_snapshot_survives_index_publish_and_expiry_is_explicit(self):
        key = self.query(query="needle", category="video")
        before = self.queries.page("owner", key, 600)
        other = self.engine.state / "replacement.db"
        self.update(other)
        with self.engine.lock:
            os.replace(other, self.engine.database)
            self.engine.metadata = {"finished_at": 2}
        self.assertEqual(self.queries.page("owner", key, 600), before)
        self.queries.get("owner", key).touched -= self.queries.TTL + 1
        with self.assertRaisesRegex(ValueError, "过期"): self.queries.page("owner", key)

    def test_csv_preserves_names_that_cannot_fit_one_path_per_line(self):
        key = self.query(query="special")
        data = {"id": key, "selection": {"all": True}}
        with self.assertRaisesRegex(ValueError, "CSV"):
            self.queries.prepare_export("owner", data)
        info = self.queries.prepare_export("owner", {**data, "format": "csv"})
        with self.queries.download("owner", info["url"].split("token=")[1]) as file:
            rows = list(csv.reader(io.StringIO(file.read().decode("utf-8-sig"))))
        self.assertEqual(len(rows), 3)
        self.assertEqual({row[1] for row in rows[1:]}, {"./special\nname.txt", "./special\\name.txt"})

    def test_cancelled_or_failed_query_cannot_export_partial_results(self):
        old = self.config["plocate"]
        script = self.base / "broken-query"
        script.write_text("#!/usr/bin/python3\nimport sys,time\nsys.stdout.buffer.write(" + repr(os.fsencode(str(self.root / 'a/needle-00000.txt')) + b'\0') + ")\nsys.stdout.flush()\nsys.exit(2)\n")
        script.chmod(0o700)
        self.config["plocate"] = str(script)
        try:
            info = self.queries.create("owner", {})
            job = self.queries.get("owner", info["id"])
            self.assertTrue(job.finished.wait(5))
            self.assertIsNotNone(job.error)
            self.assertFalse(job.complete)
            with self.assertRaises(ValueError): self.queries.selected("owner", {"id": job.id, "selection": {"all": True}})
        finally: self.config["plocate"] = old
