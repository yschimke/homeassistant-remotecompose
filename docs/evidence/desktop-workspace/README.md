# Terrazzo desktop workspace evidence

These 1364×1024 images come from the running Linux X11 JVM app. All dashboards, entities,
readings and credentials are synthetic; no real Home Assistant instance was used.

- `01-overview.png`: the dashboard sidebar and existing live card renderer.
- `02-dashboard-menu.png`: switching to Upstairs and the native navigation shortcuts.
- `03-edit-protection.png`: sidebar and native navigation disabled while editing.

## Reproduce the fixture

The app's sign-in requires HTTPS. Generate a temporary localhost certificate and add it to a
copy of the JDK truststore for this test process; production connection rules are unchanged.
The generated certificate, private key and truststore are not committed.

```sh
mkdir -p /tmp/terrazzo-demo-tls
openssl req -x509 -newkey rsa:2048 -nodes -days 2 -subj /CN=localhost \
  -addext subjectAltName=DNS:localhost,IP:127.0.0.1 \
  -keyout /tmp/terrazzo-demo-tls/key.pem -out /tmp/terrazzo-demo-tls/cert.pem
cp "$JAVA_HOME/lib/security/cacerts" /tmp/terrazzo-demo-tls/truststore
keytool -importcert -noprompt -alias terrazzo-desktop-demo \
  -file /tmp/terrazzo-demo-tls/cert.pem \
  -keystore /tmp/terrazzo-demo-tls/truststore -storepass changeit
python3 -m venv /tmp/terrazzo-demo-venv
/tmp/terrazzo-demo-venv/bin/pip install websockets==16.0
/tmp/terrazzo-demo-venv/bin/python docs/evidence/desktop-workspace/demo-server.py \
  --cert /tmp/terrazzo-demo-tls/cert.pem --key /tmp/terrazzo-demo-tls/key.pem
```

In a second terminal, create `/tmp/terrazzo-demo.init.gradle`:

```groovy
allprojects {
    tasks.withType(JavaExec).configureEach {
        systemProperty 'user.home', '/tmp/terrazzo-desktop-smoke'
        systemProperty 'javax.net.ssl.trustStore', '/tmp/terrazzo-demo-tls/truststore'
        systemProperty 'javax.net.ssl.trustStorePassword', 'changeit'
    }
}
```

Run `./gradlew -I /tmp/terrazzo-demo.init.gradle :app-ui:run` and connect to
`https://localhost:18124` with the fixture token `desktop-demo-token`.
The fixture accepts reads and subscriptions; dashboard writes are unsupported.

Validation: JVM, Android shared, and Wasm shared compilation; three workspace and six existing
header tests; manual Linux switching, Home/Settings/Edit shortcuts, navigation suppression while
editing, command revocation after sign-out, and F11 bounds restoration. macOS menu/Dock behavior,
Wayland, and real Home Assistant installations still need platform/integration verification.
