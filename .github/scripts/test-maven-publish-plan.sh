#!/usr/bin/env bash
# Self-test for maven-publish-plan.sh, run by CI on every PR.
#
# The plan only ever runs during a release, and both of its failure directions are quiet: too broad
# and a release uploads every library for a comment edit, too narrow and a library whose POM moved
# is never uploaded, which Central cannot repair. Each case builds a throwaway repository with a
# baseline tag, makes one change, and pins the exact set the plan prints. Ported from
# yschimke/compose-ui-builder's copy, for this repository's `harc.publishing` libraries.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UNDER_TEST="${SCRIPT_DIR}/maven-publish-plan.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "${tmp}"' EXIT

failures=0
fail() {
  echo "FAIL: $1" >&2
  failures=$((failures + 1))
}

ALL="alpha beta gamma"

repo="${tmp}/repo"
mkdir -p "${repo}"
cd "${repo}" || exit 1
git init -q -b main
git config user.email test@example.com
git config user.name test

mkdir -p alpha beta internal docs lib/gamma ha-bom build-logic/src/main/kotlin build-logic/src/test/kotlin gradle
cat > settings.gradle.kts <<'EOF'
include(
  ":alpha",
  ":beta",
)
include(":gamma", ":internal")
include(":ha-bom")
project(":gamma").projectDir = file("lib/gamma")

dependencyResolutionManagement {
  versionCatalogs {
    // Snapshot mode overrides the ref; that writes the entry, it does not use it.
    create("libs") { version("okio", "9.9.9-SNAPSHOT") }
  }
}
EOF
cat > build.gradle.kts <<'EOF'
// The root build.
plugins { alias(libs.plugins.kotlin.jvm) apply false }

tasks.register("hello") { doLast { println("hi") } }
EOF
cat > alpha/build.gradle.kts <<'EOF'
plugins { id("harc.publishing") }
dependencies { implementation(libs.okio) }
// Packs the output of an unpublished sibling, like render-bundle packs :ui-builder.
val packed = project(":internal").layout.buildDirectory
EOF
cat > beta/build.gradle.kts <<'EOF'
plugins { id("harc.publishing") }
dependencies {
  implementation(project(":alpha"))
  implementation(
    libs.androidx
      .core
      .ktx
  )
}
EOF
cat > lib/gamma/build.gradle.kts <<'EOF'
plugins {
  id("harc.publishing")
  alias(libs.plugins.wire)
}
dependencies {
  implementation(libs.bundles.net)
  api(libs.composeai.ui.builder.protocol)
}
val fixture = rootProject.file("docs/data.json")
// A test reading a directory beside the module (rc-converter reads ../terrazzo-core/…/dashboards).
val shared = layout.projectDirectory.dir("../../shared-fixtures")
// Mentions project(":internal") in prose only: not an input (compose-preview-daemon#197).
/* val packed = project(":internal").layout.buildDirectory */
EOF
cat > ha-bom/build.gradle.kts <<'EOF'
plugins {
  `java-platform`
  id("harc.publishing")
}
dependencies { constraints { api(project(":alpha")) } }
EOF
echo 'plugins { kotlin("jvm") }' > internal/build.gradle.kts
echo '{}' > docs/data.json
mkdir -p shared-fixtures && echo '{}' > shared-fixtures/dashboards.json
cat > build-logic/src/main/kotlin/Conventions.kt <<'EOF'
package conventions

// Reads the Kotlin version off the catalog.
fun kotlinVersion(libs: Any) = find(libs, "kotlinCore")

fun find(libs: Any, name: String): String = name
EOF
cat > build-logic/src/main/kotlin/Raw.kt <<'EOF'
package conventions

val banner = """
// not a comment: this is inside a raw string
"""
EOF
cat > build-logic/src/test/kotlin/ConventionsTest.kt <<'EOF'
package conventions

class ConventionsTest
EOF
cat > gradle/libs.versions.toml <<'EOF'
[versions]
# The Kotlin core libraries.
kotlinCore = "2.1.0"
kotlin = "2.1.0"
okio = "3.9.0"
okhttp = "4.12.0"
androidx-core = "1.13.0"
wire = "5.0.0"
unused = "1.0"
composeai-contracts = "3.0.0"

[libraries]
composeai-ui-builder-protocol = { module = "ee.schimke.composeai:ui-builder-protocol", version.ref = "composeai-contracts" }
okio = { module = "com.squareup.okio:okio", version.ref = "okio" }
okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
okhttp-logging = { module = "com.squareup.okhttp3:logging-interceptor", version.ref = "okhttp" }
androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "androidx-core" }
kotlin-stdlib = { module = "org.jetbrains.kotlin:kotlin-stdlib", version.ref = "kotlinCore" }

[bundles]
net = ["okhttp", "okhttp-logging"]

[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
wire = { id = "com.squareup.wire", version.ref = "wire" }
EOF
git add -A
git commit -q -m base
git tag v1.0.0

printf '{"modules":{%s}}\n' "$(for a in ${ALL}; do printf '"%s":"1.0.0",' "${a}"; done | sed 's/,$//')" \
  > "${tmp}/manifest.json"

# check <name> <expected set, space separated> — runs a case from a clean checkout of the baseline.
# The change is applied by the function named `change_<name>` and committed first.
check() {
  local name="$1" expected="$2" actual
  git checkout -q -f v1.0.0
  git clean -qfdx
  git checkout -q -B "case-${name}"
  "change_${name}"
  git add -A
  git commit -q --allow-empty -m "${name}"
  actual="$("${UNDER_TEST}" --head HEAD --manifest "${tmp}/manifest.json" 2> "${tmp}/${name}.log" |
    tr '\n' ' ' | sed 's/ $//')"
  if [ "${actual}" != "${expected}" ]; then
    fail "${name}: expected [${expected}], got [${actual}]"
    sed 's/^/    /' "${tmp}/${name}.log" >&2
  else
    echo "ok: ${name}"
  fi
}

# Nothing changed: nothing publishes.
change_nothing() { :; }
check nothing ""

# v2.21.2: a comment-only edit to the root build.
change_root_comment_only() {
  python3 - <<'PY'
p = "build.gradle.kts"
s = open(p).read().replace("// The root build.\n", "// The root build, reworded.\n\n  // And a new line.\n")
open(p, "w").write(s)
PY
}
check root_comment_only ""

# A real edit to the root build still publishes everything.
change_root_code() { sed -i 's/println("hi")/println("hello")/' build.gradle.kts; }
check root_code "${ALL}"

# A trailing comment after code is not judged: only whole-line comments are.
change_root_trailing_comment() { sed -i 's/apply false }/apply false } \/\/ root plugins/' build.gradle.kts; }
check root_trailing_comment "${ALL}"

# v2.21.1: a build-logic test change on its own.
change_build_logic_test_only() { echo 'class AnotherTest' >> build-logic/src/test/kotlin/ConventionsTest.kt; }
check build_logic_test_only ""

# build-logic's main sources still publish everything.
change_build_logic_main() { echo 'val x = 1' >> build-logic/src/main/kotlin/Conventions.kt; }
check build_logic_main "${ALL}"

# A build-logic main source moved into a test directory is still a main-source change: rename
# detection would report only the (test-only) destination and exempt it.
change_build_logic_main_moved_to_test() { git mv build-logic/src/main/kotlin/Conventions.kt build-logic/src/test/kotlin/Conventions.kt; }
check build_logic_main_moved_to_test "${ALL}"

# A comment edit in build-logic main is comment-only.
change_build_logic_comment() { sed -i 's|// Reads the Kotlin version off the catalog.|// Reads it.|' build-logic/src/main/kotlin/Conventions.kt; }
check build_logic_comment ""

# ...but not in a file holding a raw string, where `//` at line start may be content.
change_raw_string_comment() { sed -i 's|// not a comment|// still not a comment|' build-logic/src/main/kotlin/Raw.kt; }
check raw_string_comment "${ALL}"

# v2.25.0 / v2.23.0: a catalog bump used by one module publishes it and its dependents only.
# `okio` is also named in a settings `version("okio", …)` override, which must not count as a use.
change_catalog_one_module() { sed -i 's/okio = "3.9.0"/okio = "3.10.0"/' gradle/libs.versions.toml; }
check catalog_one_module "alpha beta"

# An accessor ktfmt wrapped across lines (`libs.androidx\n.core\n.ktx`) is still found.
change_catalog_wrapped_accessor() { sed -i 's/androidx-core = "1.13.0"/androidx-core = "1.15.0"/' gradle/libs.versions.toml; }
check catalog_wrapped_accessor "beta"

# A version ref reaches a bundle through its libraries.
change_catalog_bundle_via_ref() { sed -i 's/okhttp = "4.12.0"/okhttp = "5.0.0"/' gradle/libs.versions.toml; }
check catalog_bundle_via_ref "gamma"

# A plugin alias used by one module.
change_catalog_plugin() { sed -i 's/wire = "5.0.0"/wire = "5.1.0"/' gradle/libs.versions.toml; }
check catalog_plugin "gamma"

# A catalog entry nobody uses publishes nothing.
change_catalog_unused() { sed -i 's/unused = "1.0"/unused = "2.0"/' gradle/libs.versions.toml; }
check catalog_unused ""

# A comment-only catalog edit publishes nothing.
change_catalog_comment() { sed -i 's/# The Kotlin core libraries./# Kotlin./' gradle/libs.versions.toml; }
check catalog_comment ""

# v2.24.0: a catalog entry build-logic reads (by string, `find(libs, "kotlinCore")`) publishes all.
change_catalog_used_by_build_logic() { sed -i 's/kotlinCore = "2.1.0"/kotlinCore = "2.2.0"/' gradle/libs.versions.toml; }
check catalog_used_by_build_logic "${ALL}"

# A catalog entry the root build uses (`libs.plugins.kotlin.jvm`) publishes all.
change_catalog_used_by_root() { sed -i 's/^kotlin = "2.1.0"/kotlin = "2.2.0"/' gradle/libs.versions.toml; }
check catalog_used_by_root "${ALL}"

# A catalog the plan cannot parse publishes all.
change_catalog_unparseable() { echo 'this is = = not toml' >> gradle/libs.versions.toml; }
check catalog_unparseable "${ALL}"

# A section the plan does not model publishes all.
change_catalog_unknown_section() { printf '\n[metadata]\nformat.version = "1.1"\n' >> gradle/libs.versions.toml; }
check catalog_unknown_section "${ALL}"

# The wrapper and other gradle/ files are still shared.
change_gradle_other() { mkdir -p gradle/wrapper && echo 'distributionUrl=x' > gradle/wrapper/gradle-wrapper.properties; }
check gradle_other "${ALL}"

# A module change still propagates to dependents alongside a catalog change.
change_catalog_and_module() {
  sed -i 's/wire = "5.0.0"/wire = "5.1.0"/' gradle/libs.versions.toml
  echo '// touched' >> alpha/build.gradle.kts
}
check catalog_and_module "${ALL}"

# A module packing an unpublished sibling's output publishes when that sibling changes, and so do
# its dependents (render-bundle <- :ui-builder, then runtime).
change_unpublished_dependency() { echo 'class X' > internal/X.kt; }
check unpublished_dependency "alpha beta"

# A file a script reads through rootProject.file(...) belongs to that module.
change_root_file_input() { echo '{"a":1}' > docs/data.json; }
check root_file_input "gamma"

# A literal "../" path watches only the directory it names, not the whole repository: a change
# there publishes the module, a change elsewhere does not (the unrelated_file case below).
change_relative_path_input() { echo '{"a":1}' > shared-fixtures/dashboards.json; }
check relative_path_input "gamma"

# A change nobody's artifact contains publishes nothing.
change_unrelated_file() { echo hi > README.md; }
check unrelated_file ""

# An `ee.schimke.composeai` bump (rc-players, Livery) is an input here, unlike compose-ui-builder,
# where its own siblings are only floors: the module using it publishes.
change_catalog_composeai_dependency() { sed -i 's/composeai-contracts = "3.0.0"/composeai-contracts = "3.1.0"/' gradle/libs.versions.toml; }
check catalog_composeai_dependency "gamma"

# The BOM applies harc.publishing but is never planned: a change to it alone publishes nothing
# (it follows the plan), and it is never listed.
change_bom_only() { echo '// touched' >> ha-bom/build.gradle.kts; }
check bom_only ""

# A test-only change in a module publishes nothing: no artifact carries a test source set. Covers
# `src/test`, a KMP `src/<target>Test` set, and an unpublished module that a published one packs.
change_module_test_only() {
  mkdir -p alpha/src/test/kotlin lib/gamma/src/jvmTest/kotlin internal/src/commonTest/kotlin
  echo 'class AlphaTest' > alpha/src/test/kotlin/AlphaTest.kt
  echo 'class GammaTest' > lib/gamma/src/jvmTest/kotlin/GammaTest.kt
  echo 'class InternalTest' > internal/src/commonTest/kotlin/InternalTest.kt
  # A target name with digits, as the KMP native targets have.
  mkdir -p lib/gamma/src/iosArm64Test/kotlin
  echo 'class GammaArmTest' > lib/gamma/src/iosArm64Test/kotlin/GammaArmTest.kt
}
check module_test_only ""

# ...but `src/testFixtures` is published as a variant by `java-test-fixtures`, so it counts.
change_module_test_fixtures() {
  mkdir -p alpha/src/testFixtures/kotlin
  echo 'class AlphaFixture' > alpha/src/testFixtures/kotlin/AlphaFixture.kt
}
check module_test_fixtures "alpha beta"

# A test change beside a main change still publishes the module.
change_module_test_and_main() {
  mkdir -p alpha/src/test/kotlin alpha/src/main/kotlin
  echo 'class AlphaTest' > alpha/src/test/kotlin/AlphaTest.kt
  echo 'class Alpha' > alpha/src/main/kotlin/Alpha.kt
}
check module_test_and_main "alpha beta"


if [ "${failures}" -gt 0 ]; then
  echo "${failures} failure(s)" >&2
  exit 1
fi
echo "all maven-publish-plan cases passed"
