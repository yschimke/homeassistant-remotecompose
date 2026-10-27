# Live widget updates

The launcher widgets and the Wear widgets draw Home Assistant states the phone app keeps on disk.
While the app holds a session that is enough; once it doesn't, the Terrazzo integration wakes it
when an entity a widget shows visibly changes, with the same content-free FCM wake as
[notifications](notifications.md).

## Opting in, per widget

Live updates are a setting of each widget, off by default: an off widget shows what the app last
saw, as before. The launcher widget's editor (long-press it, or tap it on the app's Widgets screen)
has a **Live updates** switch, and each watch widget's row on the Widgets screen has one too.
With every switch off the integration is told no entities, and nothing wakes the app.

## What wakes the app

The app tells the integration which entities its live widgets read: every entity in the card of a
launcher widget with live updates on, on that Home Assistant, plus every entity in the card of a
watch widget with live updates on (`terrazzo/push/watch`). The watch widgets' entities are kept on
the phone, so a process started for a wake reports the same set. The integration follows only those (`state_wakes.py`) and judges each
change by what a person would notice:

| Change | Example | Wake |
| --- | --- | --- |
| A movement starts or ends | `closed` → `opening`, `opening` → `open` | Urgent: high priority, 10 s apart at most |
| The state flips | `off` → `on`, `home` → `not_home`, → `unavailable` | Routine: normal priority, 5 min apart at most |
| A number ticks | a power sensor `120.5` → `121` | None |
| Attributes only | a light's brightness | None |

A routine wake goes at normal priority, so FCM may hold it while the phone dozes. Changes inside
the interval fold into one wake at its end. A device gets 400 state wakes a day, below the relay's
500, which notifications share. A number ticking, or an attribute, is drawn the next time the app
wakes or connects for any reason.

Moving states, per domain: `cover` and `valve` `opening`/`closing`; `lock`
`locking`/`unlocking`/`opening`; `alarm_control_panel` `arming`/`disarming`/`pending`.

The wake is `{type: "state", webhook_id}`. It names no entity and carries no state; the app reads
those over its own authenticated websocket, as it does for notifications.

## Staying awake while something moves

On a state wake the app runs an expedited job (`WidgetStateWorker`). It connects with the stored
login and follows the states (`LiveWidgetUpdates.follow`), redrawing the widgets that read a
changed entity and sending the watch its values:

- at once for a visible edge (one redraw a second at most);
- once a minute at most for a number or an attribute.

It stays while any watched entity is moving, and for 20 s after the last change, up to 2 minutes.
A garage door that takes 15 s to open draws `opening` within seconds of the wake and `open` as it
lands, from one wake.

## The lease

While the app follows the states itself, from that window or from its own session while it is open,
it holds a 90 s lease on the integration (`terrazzo/push/watch` with `lease_seconds`), renewed every
45 s, and the integration sends no state wakes. Leaving the window releases it, so the next change
wakes the app at once.

A change during a lease that is then not renewed (the process was frozen or killed) is woken for
when the lease runs out.

## Limits

- Without the integration, or with one older than state wakes, widgets keep up only while the app
  holds a session; `terrazzo/push/watch` answers `unknown_command` and the app carries on.
- Without Firebase (no `google-services.json`) there are no wakes at all.
- A process woken cold doesn't know the watch's cards until its session publishes them; it uses the
  entities in the last `/wear/values` item it wrote.
- A device watches up to 500 entities.
