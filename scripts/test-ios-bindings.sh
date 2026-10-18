#!/usr/bin/env bash
# Test the app's Foundation-only models and live transport without an iOS simulator.
set -euo pipefail
repo_root=$(git rev-parse --show-toplevel)
bindings_test_dir=$(mktemp -d)
trap 'rm -rf "$bindings_test_dir"' EXIT
mkdir -p "$bindings_test_dir/Sources/TerrazzoBindingModels" "$bindings_test_dir/Tests/BindingValueTests"
for source in Models LiveBindingStream RemoteComposeService CardCache CredentialStore CardDocumentGenerator DemoContent; do
  cp "$repo_root/ios/Terrazzo/$source.swift" "$bindings_test_dir/Sources/TerrazzoBindingModels/"
done
# The store tests exercise networking/state on macOS; UIKit URL opening is built by xcodebuild.
sed 's/import UIKit/import Combine/; s/UIApplication.shared.open(url)/_ = url/' \
  "$repo_root/ios/Terrazzo/DashboardStore.swift" > "$bindings_test_dir/Sources/TerrazzoBindingModels/DashboardStore.swift"
cp "$repo_root/ios/Tests/"*Tests.swift "$bindings_test_dir/Tests/BindingValueTests/"
cp -R "$repo_root/ios/Tests/Fixtures" "$bindings_test_dir/Tests/BindingValueTests/"
cat > "$bindings_test_dir/Package.swift" <<'PACKAGE'
// swift-tools-version:5.9
import PackageDescription
let package = Package(name: "TerrazzoBindingModels", platforms: [.macOS(.v13)], targets: [
  .target(name: "TerrazzoBindingModels"),
  .testTarget(name: "BindingValueTests", dependencies: ["TerrazzoBindingModels"], exclude: ["Fixtures"]),
])
PACKAGE
swift test --package-path "$bindings_test_dir" "$@"
