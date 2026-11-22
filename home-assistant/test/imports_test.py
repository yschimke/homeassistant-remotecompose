"""Every name the integration imports from one of its own modules (`from .const import MCP_URL`)
exists there. The other tests load modules one at a time, outside Home Assistant, so a name removed
from a module another imports would pass them all and only fail when Home Assistant loads the
integration."""
import ast
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2] / 'custom_components/terrazzo'


def defined(tree):
    """Top-level names [tree] defines: assignments, functions, classes and its own imports."""
    names = set()
    for node in tree.body:
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
            names.add(node.name)
        elif isinstance(node, ast.Assign):
            names.update(t.id for t in node.targets if isinstance(t, ast.Name))
        elif isinstance(node, ast.AnnAssign) and isinstance(node.target, ast.Name):
            names.add(node.target.id)
        elif isinstance(node, (ast.Import, ast.ImportFrom)):
            names.update((alias.asname or alias.name).split('.')[0] for alias in node.names)
        elif isinstance(node, ast.Try):
            # `try: from . import mcp` / `except ImportError: … mcp = …`: names either side binds.
            names.update(defined(ast.Module(body=node.body, type_ignores=[])))
            for handler in node.handlers:
                names.update(defined(ast.Module(body=handler.body, type_ignores=[])))
    return names


class ImportsTest(unittest.TestCase):
    def test_every_name_imported_from_a_sibling_module_exists(self):
        modules = {path.stem: ast.parse(path.read_text()) for path in ROOT.glob('*.py')}
        exports = {name: defined(tree) for name, tree in modules.items()}
        missing = []
        for name, tree in modules.items():
            for node in ast.walk(tree):
                if not isinstance(node, ast.ImportFrom) or node.level != 1:
                    continue
                if node.module is None:
                    # `from . import mcp`: a sibling module.
                    missing += [f'{name}: .{a.name}' for a in node.names if a.name not in modules]
                elif node.module in modules:
                    missing += [
                        f'{name}: .{node.module}.{a.name}'
                        for a in node.names
                        if a.name != '*' and a.name not in exports[node.module]
                    ]
        self.assertEqual([], missing)


if __name__ == '__main__':
    unittest.main()
