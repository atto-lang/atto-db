import unittest
from reference.migration_plan import MigrationPlanError, plan_migrations


class MigrationPlanTest(unittest.TestCase):
    def test_deterministic_order_and_applied(self):
        items = [
            {"id": "seed", "depends_on": ["schema"]},
            {"id": "audit", "depends_on": ["schema"]},
            {"id": "schema", "depends_on": []},
        ]
        self.assertEqual(plan_migrations(items), ["schema", "audit", "seed"])
        self.assertEqual(plan_migrations(list(reversed(items)), applied=["schema"]),
                         ["audit", "seed"])

    def test_unknown_cycle_and_applied_inconsistency(self):
        for items, applied in [
            ([{"id": "a", "depends_on": ["missing"]}], []),
            ([{"id": "a", "depends_on": ["b"]},
              {"id": "b", "depends_on": ["a"]}], []),
            ([{"id": "a", "depends_on": []},
              {"id": "b", "depends_on": ["a"]}], ["b"]),
        ]:
            with self.assertRaises(MigrationPlanError):
                plan_migrations(items, applied)

    def test_reject_ambiguous_manifest(self):
        items = [{"id": "one", "depends_on": []}]
        for invalid in [
            [{"id": "one", "depends_on": []}, items[0]],
            [{"id": "one", "depends_on": [], "sql": "DROP TABLE x"}],
            [{"id": "One", "depends_on": []}],
            [{"id": "one", "depends_on": ["one", "one"]}],
        ]:
            with self.assertRaises(MigrationPlanError):
                plan_migrations(invalid)
        with self.assertRaises(MigrationPlanError):
            plan_migrations(items, applied=["one", "one"])


if __name__ == "__main__":
    unittest.main()
