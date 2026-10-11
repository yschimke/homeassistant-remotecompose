# Climate tiles keep their active accent under live bindings

`DashboardClimateMobile`, rendered with `compose-preview render --module previews --filter DashboardClimateMobile` (CLI 2.45.0).

- `dashboard-climate-mobile-before.png` (downscaled to 1800px tall): `main` @ ce5b00b. The three `heat` thermostats render grey: the pushed `is_on` was `state == "on"`, false for `heat`.
- `dashboard-climate-mobile-after.png`: this branch. They keep the orange active accent the converter bakes.
- `thermostats-before-after.png`: crop of the only changed region, bbox (27,1216)-(63,1359). No other pixel differs.
