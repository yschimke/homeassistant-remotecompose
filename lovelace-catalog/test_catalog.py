import json
import pathlib
import tempfile
import unittest
from generate import generate


class CatalogTest(unittest.TestCase):
    def test_dashboard_hierarchy_and_grid_constraints(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            catalog = generate(root)
            builtins = catalog["statusSemantics"]["builtins"]
            self.assertEqual(["lovelace/dashboard"], [k for k, v in builtins.items() if v["role"] == "screen-root"])
            for parent, slot, trait in [("dashboard", "views", "LovelaceView"), ("view", "sections", "LovelaceSection"), ("section", "cards", "LovelaceCard")]:
                self.assertEqual([trait], builtins[f"lovelace/{parent}"]["slots"][slot]["acceptedTraits"])
            self.assertEqual({}, builtins["lovelace/opaque-view"]["slots"])
            for key, node in builtins.items():
                self.assertEqual([], node["modifierCapabilities"])
                self.assertIn("rawConfig", [p["name"] for p in node["properties"]])
                if key.startswith("lovelace/card/"):
                    properties = {p["name"]: p for p in node["properties"]}
                    self.assertEqual(list(range(1, 13)) + ["full"], properties["grid_columns"]["allowedValues"])
            manifest = json.loads((root / "catalog.json").read_text())
            self.assertTrue((root / manifest["uiBuilderFile"]).is_file())
            self.assertEqual("lovelace-config", catalog["statusSemantics"]["composeSourceExport"]["adapter"])


if __name__ == "__main__":
    unittest.main()
