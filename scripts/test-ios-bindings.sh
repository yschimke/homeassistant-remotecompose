#!/usr/bin/env bash
# Test the app's Foundation-only JSON binding model without launching an iOS simulator.
set -euo pipefail
repo_root=$(git rev-parse --show-toplevel)
bindings_test_dir=$(mktemp -d)
trap 'rm -rf "$bindings_test_dir"' EXIT
mkdir -p "$bindings_test_dir/Sources/TerrazzoBindingModels" "$bindings_test_dir/Tests/BindingValueTests"
cp "$repo_root/ios/Terrazzo/Models.swift" "$bindings_test_dir/Sources/TerrazzoBindingModels/"
cp "$repo_root/ios/Tests/BindingValueTests.swift" "$bindings_test_dir/Tests/BindingValueTests/"
cat > "$bindings_test_dir/Package.swift" <<'PACKAGE'
// swift-tools-version:5.9
import PackageDescription
let package = Package(name: "TerrazzoBindingModels", targets: [
  .target(name: "TerrazzoBindingModels"),
  .testTarget(name: "BindingValueTests", dependencies: ["TerrazzoBindingModels"]),
])
PACKAGE
swift test --package-path "$bindings_test_dir" "$@"
