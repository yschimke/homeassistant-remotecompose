# Changelog

## [0.1.4](https://github.com/yschimke/homeassistant-remotecompose/compare/v0.1.3...v0.1.4) (2026-05-07)


### Features

* **clock:** bind RemoteTimeDefaults so the clock card ticks live ([#88](https://github.com/yschimke/homeassistant-remotecompose/issues/88)) ([3427890](https://github.com/yschimke/homeassistant-remotecompose/commit/3427890e925848ceba63ec29f71dc890b2718a0f))
* **demo:** replace synthetic demo with the seven captured dashboards ([#98](https://github.com/yschimke/homeassistant-remotecompose/issues/98)) ([fea40e6](https://github.com/yschimke/homeassistant-remotecompose/commit/fea40e691e1fa740d49424975b1cb8ad83d7a666))
* **previews:** whole-dashboard previews at mobile + tablet widths ([#97](https://github.com/yschimke/homeassistant-remotecompose/issues/97)) ([91e0df9](https://github.com/yschimke/homeassistant-remotecompose/commit/91e0df98f9a6893d324b4a24e56905bf44b5dfb7))


### Bug Fixes

* **deps:** update dependency androidx.compose:compose-bom to v2026.05.00 ([#94](https://github.com/yschimke/homeassistant-remotecompose/issues/94)) ([b941e0c](https://github.com/yschimke/homeassistant-remotecompose/commit/b941e0c8a2b880a6142f0fe3b798d6960984a4d7))
* **deps:** update dependency androidx.tv:tv-material to v1.1.0 ([#95](https://github.com/yschimke/homeassistant-remotecompose/issues/95)) ([13d5d31](https://github.com/yschimke/homeassistant-remotecompose/commit/13d5d31d76a21445df36d8a339a2e695a5ea1a3a))
* **deps:** update dependency org.hamcrest:hamcrest to v3 ([#96](https://github.com/yschimke/homeassistant-remotecompose/issues/96)) ([6f85ab1](https://github.com/yschimke/homeassistant-remotecompose/commit/6f85ab14f3787637ebbaaded29af8d7f2875401c))
* **deps:** update remote-compose ([#93](https://github.com/yschimke/homeassistant-remotecompose/issues/93)) ([29fe027](https://github.com/yschimke/homeassistant-remotecompose/commit/29fe027beddfbab60fd0739ecf776e7e7f602a68))

## [0.1.3](https://github.com/yschimke/homeassistant-remotecompose/compare/v0.1.2...v0.1.3) (2026-05-07)


### Features

* **bambu:** real progress ring + sensor readouts in print_status card ([#80](https://github.com/yschimke/homeassistant-remotecompose/issues/80)) ([a9c3156](https://github.com/yschimke/homeassistant-remotecompose/commit/a9c3156feaadf5ed1bc5e2892541e36683458095))
* **bambu:** real renderings for ams, spool, print_control cards ([#81](https://github.com/yschimke/homeassistant-remotecompose/issues/81)) ([1438f25](https://github.com/yschimke/homeassistant-remotecompose/commit/1438f2553152f3b4b825233488b40edd880f2386))
* **history-graph:** draw real sparklines via RemoteCanvas ([#79](https://github.com/yschimke/homeassistant-remotecompose/issues/79)) ([da15610](https://github.com/yschimke/homeassistant-remotecompose/commit/da15610fb2dbf88cf83d4692dd39283953652c64))
* implement alarm-panel, media-control, and todo-list cards ([#84](https://github.com/yschimke/homeassistant-remotecompose/issues/84)) ([708e7d1](https://github.com/yschimke/homeassistant-remotecompose/commit/708e7d123f7ec264e5bbc8a0ff369dde633cc7d1))
* implement calendar and area cards ([#85](https://github.com/yschimke/homeassistant-remotecompose/issues/85)) ([fb8b0fc](https://github.com/yschimke/homeassistant-remotecompose/commit/fb8b0fc31a548a9c4780b0320e52abbde6180624))
* implement clock and statistics-graph cards ([#83](https://github.com/yschimke/homeassistant-remotecompose/issues/83)) ([0d4daa9](https://github.com/yschimke/homeassistant-remotecompose/commit/0d4daa9d6e41c025605f25ea5523db20348c1144))
* implement gauge, weather-forecast, picture-entity, logbook cards ([#77](https://github.com/yschimke/homeassistant-remotecompose/issues/77)) ([26ea0bd](https://github.com/yschimke/homeassistant-remotecompose/commit/26ea0bdf1ea5a88dcee9c2212bacace489bedbae))
* implement picture, picture-glance, picture-elements cards ([#86](https://github.com/yschimke/homeassistant-remotecompose/issues/86)) ([9fb8af2](https://github.com/yschimke/homeassistant-remotecompose/commit/9fb8af28e3ebb8a7ee254d2e83136b60fbdf7e65))
* implement statistic, sensor, entity-filter cards ([#87](https://github.com/yschimke/homeassistant-remotecompose/issues/87)) ([28cc8f1](https://github.com/yschimke/homeassistant-remotecompose/commit/28cc8f1495ad2fe71bcd48aadf73759a30a7c009))
* implement thermostat, humidifier, and light cards via shared arc dial ([#82](https://github.com/yschimke/homeassistant-remotecompose/issues/82)) ([22e4000](https://github.com/yschimke/homeassistant-remotecompose/commit/22e40000f323f16433d72fc6eebd20f018ff8909))
* **play:** replace icon and feature-graphic placeholders ([#61](https://github.com/yschimke/homeassistant-remotecompose/issues/61)) ([75c797f](https://github.com/yschimke/homeassistant-remotecompose/commit/75c797f0c97e65775946cad56823701dfdc5af98))
* **play:** replace placeholder screenshots with rendered previews ([#59](https://github.com/yschimke/homeassistant-remotecompose/issues/59)) ([413ba04](https://github.com/yschimke/homeassistant-remotecompose/commit/413ba0419f8afecbfe689bdbb13e2b8ebfc1c63a))
* **play:** replace placeholder screenshots with rendered previews ([#60](https://github.com/yschimke/homeassistant-remotecompose/issues/60)) ([a0976b1](https://github.com/yschimke/homeassistant-remotecompose/commit/a0976b1250bcd5549ef84ade24cd688fdbeefcfd))
* **rc-components-ui:** plain-Compose Tier-2 wrappers around RemoteHa* ([#65](https://github.com/yschimke/homeassistant-remotecompose/issues/65)) ([805e6dd](https://github.com/yschimke/homeassistant-remotecompose/commit/805e6dd235e7fbcad2a5fefdf786b63686b07d38))


### Bug Fixes

* **deps:** update dependency com.google.android.gms:play-services-wearable to v20 ([#73](https://github.com/yschimke/homeassistant-remotecompose/issues/73)) ([8f9aae5](https://github.com/yschimke/homeassistant-remotecompose/commit/8f9aae599373d167e224e9a1b5632dd560f49165))
