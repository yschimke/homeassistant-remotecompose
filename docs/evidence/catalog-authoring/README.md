These 1200×900 captures show the deployed route before this change (HTTP 410) and a local,
synthetic authenticated admin importing an actual pinned HA source document. No production
write was performed. The after image verifies the admin form/import, not successful native
compilation or publication. The real native smoke test is `design-server/test/catalog-native-smoke.mjs`.

The current published HA catalog reports orphaned template/theme policies and its native bundle
omits the callable facade classes for IR-backed stickers. Those producer defects must be fixed
and the native smoke test must pass before deployment/cutover. The JVM publication tests use a
mock compiler, and must not be described as UID-to-RC fidelity evidence.
