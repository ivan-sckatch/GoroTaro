"""Verify actual migration SQL against exported Room schemas using SQLite."""
import json
import re
import sqlite3
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCHEMAS = ROOT / "app/schemas/ru.gorotaro.app.data.GoroDatabase"


def create_schema(db, version):
    schema = json.loads((SCHEMAS / f"{version}.json").read_text(encoding="utf-8"))["database"]
    for entity in schema["entities"]:
        db.execute(entity["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
        for index in entity.get("indices", []):
            db.execute(index["createSql"].replace("${TABLE_NAME}", entity["tableName"]))


class HistoryMigrationTest(unittest.TestCase):
    def test_migration_keeps_old_rows_and_matches_new_schema(self):
        migrated = sqlite3.connect(":memory:")
        fresh = sqlite3.connect(":memory:")
        self.addCleanup(migrated.close)
        self.addCleanup(fresh.close)
        create_schema(migrated, 1)
        create_schema(fresh, 2)
        snapshot = '{"note":"Старая заметка","interpretation":"Сохранённый текст"}'
        migrated.execute("INSERT INTO readings VALUES (?,NULL,?,?,?,?,?,?)",
                         ("old", "2026-10-07", "day1", "key", 1000, snapshot, 1))
        source = (ROOT / "app/src/main/java/ru/gorotaro/app/data/Database.kt").read_text(encoding="utf-8")
        migration = source.split("val MIGRATION_1_2", 1)[1].split("@Database", 1)[0]
        statements = re.findall(r'db\.execSQL\("([^"]+)"\)', migration)
        self.assertEqual(2, len(statements))
        for statement in statements:
            migrated.execute(statement)
        self.assertEqual((snapshot, 1, "", 0), migrated.execute(
            "SELECT snapshot,hidden,searchText,favorite FROM readings WHERE id='old'").fetchone())
        tables = [r[0] for r in fresh.execute("SELECT name FROM sqlite_master WHERE type='table'")]
        for table in tables:
            self.assertEqual(fresh.execute(f"PRAGMA table_info('{table}')").fetchall(),
                             migrated.execute(f"PRAGMA table_info('{table}')").fetchall())
            self.assertEqual(fresh.execute(f"PRAGMA index_list('{table}')").fetchall(),
                             migrated.execute(f"PRAGMA index_list('{table}')").fetchall())
        with self.assertRaises(sqlite3.IntegrityError):
            migrated.execute("INSERT INTO readings VALUES (?,NULL,?,?,?,?,?,?,?,?)",
                             ("duplicate", "2026-10-07", "day1", "key", 2000, snapshot, 0, "", 0))


if __name__ == "__main__":
    unittest.main()
