# Changelog

## [0.1.7](https://github.com/yschimke/homeassistant-remotecompose/compare/v0.1.6...v0.1.7) (2026-05-08)


### Bug Fixes

* **ha-model:** capture full card object as CardConfig.raw ([#148](https://github.com/yschimke/homeassistant-remotecompose/issues/148)) ([53a405c](https://github.com/yschimke/homeassistant-remotecompose/commit/53a405c49497f642a1bec180a984fcd19ae31583))
* **test:** point LongPressInstallTest at the first captured demo board ([#150](https://github.com/yschimke/homeassistant-remotecompose/issues/150)) ([f250fce](https://github.com/yschimke/homeassistant-remotecompose/commit/f250fced466ee6bc6309460d7c1c62e2da23f873))

## [0.1.6](https://github.com/yschimke/homeassistant-remotecompose/compare/v0.1.5...v0.1.6) (2026-05-08)


### Features

* **app:** register enhanced-shutter card converter ([#146](https://github.com/yschimke/homeassistant-remotecompose/issues/146)) ([f8a4d3b](https://github.com/yschimke/homeassistant-remotecompose/commit/f8a4d3b75a47369db407fc1f56c34ac792f75b82))
* **components:** drive RemoteHaToggleSwitch from a host RemoteBoolean ([#145](https://github.com/yschimke/homeassistant-remotecompose/issues/145)) ([27f9a7c](https://github.com/yschimke/homeassistant-remotecompose/commit/27f9a7c37e2b73d8df20f1be38117dec34f0a3c0))
* **widget:** capture widget docs with the WIDGETS_V6 profile ([#142](https://github.com/yschimke/homeassistant-remotecompose/issues/142)) ([eca81b0](https://github.com/yschimke/homeassistant-remotecompose/commit/eca81b094de3246ca942817a404b28877363058a))
* **widget:** use WIDGETS_V7 profile (document API level 7) ([#147](https://github.com/yschimke/homeassistant-remotecompose/issues/147)) ([46772b7](https://github.com/yschimke/homeassistant-remotecompose/commit/46772b73dbdb7caf11df9a2fbce870629e6c5ac0))


### Bug Fixes

* **theme:** drop vertical padding from section-group surface ([#144](https://github.com/yschimke/homeassistant-remotecompose/issues/144)) ([019a4cc](https://github.com/yschimke/homeassistant-remotecompose/commit/019a4cca3449a46d9226ac037f36a90da85541e9))

## [0.1.5](https://github.com/yschimke/homeassistant-remotecompose/compare/v0.1.4...v0.1.5) (2026-05-08)


### Features

* **alarm-panel:** route status chrome through RemoteStateLayout(RemoteInt) ([#138](https://github.com/yschimke/homeassistant-remotecompose/issues/138)) ([6d346b2](https://github.com/yschimke/homeassistant-remotecompose/commit/6d346b2af462ce8da3137363f04b36a1e8212bd5))
* **alarm-panel:** wire keypad presses with timing-based code submit ([#140](https://github.com/yschimke/homeassistant-remotecompose/issues/140)) ([b9c94b4](https://github.com/yschimke/homeassistant-remotecompose/commit/b9c94b4415d8784c295cade7a25b2ea0f8848e4d))
* **components:** bind sparkline points to &lt;entityId&gt;.numeric.&lt;index&gt; ([#139](https://github.com/yschimke/homeassistant-remotecompose/issues/139)) ([2c28d54](https://github.com/yschimke/homeassistant-remotecompose/commit/2c28d54dbd6b53c768b908f8620da7d3f6a6c937))


### Bug Fixes

* **rc-converter:** push snapshot updates into running player by named binding ([#136](https://github.com/yschimke/homeassistant-remotecompose/issues/136)) ([f4fdb01](https://github.com/yschimke/homeassistant-remotecompose/commit/f4fdb01a725e3b2f38ec77e212686b67cb5ef0a8)), closes [#122](https://github.com/yschimke/homeassistant-remotecompose/issues/122)

## [0.1.4](https://github.com/yschimke/homeassistant-remotecompose/compare/v0.1.3...v0.1.4) (2026-05-08)


### Features

* **arc-dial:** draw target-position marker dot on the 270° sweep ([#112](https://github.com/yschimke/homeassistant-remotecompose/issues/112)) ([bd92a3e](https://github.com/yschimke/homeassistant-remotecompose/commit/bd92a3ef1910b2c1a9f8c34e5593478722bffdf7))
* **clock:** bind RemoteTimeDefaults so the clock card ticks live ([#88](https://github.com/yschimke/homeassistant-remotecompose/issues/88)) ([3427890](https://github.com/yschimke/homeassistant-remotecompose/commit/3427890e925848ceba63ec29f71dc890b2718a0f))
* **components:** in-document animations for toggle / gauge / arc dial ([#128](https://github.com/yschimke/homeassistant-remotecompose/issues/128)) ([f67ef39](https://github.com/yschimke/homeassistant-remotecompose/commit/f67ef3922dfe19bb82407febbba00e96aca7ae2b))
* **dashboard:** wire button taps to a dispatcher; demo state animates ([#131](https://github.com/yschimke/homeassistant-remotecompose/issues/131)) ([8f36c4d](https://github.com/yschimke/homeassistant-remotecompose/commit/8f36c4dcd02ded4fc251d840a1f71c197a37f2d3))
* **dashboard:** wire live HA service calls for non-demo sessions ([#132](https://github.com/yschimke/homeassistant-remotecompose/issues/132)) ([14f3d24](https://github.com/yschimke/homeassistant-remotecompose/commit/14f3d24167ea70d022a234c0d98212932bd766bb))
* **demo:** replace synthetic demo with the seven captured dashboards ([#98](https://github.com/yschimke/homeassistant-remotecompose/issues/98)) ([fea40e6](https://github.com/yschimke/homeassistant-remotecompose/commit/fea40e691e1fa740d49424975b1cb8ad83d7a666))
* **previews:** pack sections into N columns on tablet ([#110](https://github.com/yschimke/homeassistant-remotecompose/issues/110)) ([5a8de3a](https://github.com/yschimke/homeassistant-remotecompose/commit/5a8de3a35a5899aee81b0e7a6e5244ffc2027534))
* **previews:** whole-dashboard previews at mobile + tablet widths ([#97](https://github.com/yschimke/homeassistant-remotecompose/issues/97)) ([91e0df9](https://github.com/yschimke/homeassistant-remotecompose/commit/91e0df98f9a6893d324b4a24e56905bf44b5dfb7))
* **rc-components:** basic markdown parser for markdown card ([#133](https://github.com/yschimke/homeassistant-remotecompose/issues/133)) ([894744d](https://github.com/yschimke/homeassistant-remotecompose/commit/894744d99634f2309b41ca567634141ddfae6ecc))
* split named / url image forms; add HaEmbeddedPlayer ([#125](https://github.com/yschimke/homeassistant-remotecompose/issues/125)) ([ff58da9](https://github.com/yschimke/homeassistant-remotecompose/commit/ff58da9b2894177a69d59125bda061a408ac4584))
* **statistics-graph:** bar chart, multi-stat fan-out, stacking ([#115](https://github.com/yschimke/homeassistant-remotecompose/issues/115)) ([b94029a](https://github.com/yschimke/homeassistant-remotecompose/commit/b94029aafb8c611bce03389c60f22227ed2d75d0))
* **theme:** apply M3 surface elevation + section grouping to rich palettes ([#134](https://github.com/yschimke/homeassistant-remotecompose/issues/134)) ([8a061c0](https://github.com/yschimke/homeassistant-remotecompose/commit/8a061c06909f8d35c8519d71e13aa58a6f88a964))
* **todo-list:** wire row taps to todo.update_item ([#111](https://github.com/yschimke/homeassistant-remotecompose/issues/111)) ([f30041d](https://github.com/yschimke/homeassistant-remotecompose/commit/f30041d976750f1b8c626b4b95f8b0082e78e6e3))


### Bug Fixes

* **clock:** live-tick the time_zone / show_seconds variants ([#118](https://github.com/yschimke/homeassistant-remotecompose/issues/118)) ([f48e783](https://github.com/yschimke/homeassistant-remotecompose/commit/f48e783ff3f2fc2f809570af13cc531b0655f469)), closes [#108](https://github.com/yschimke/homeassistant-remotecompose/issues/108)
* **deps:** update dependency androidx.compose:compose-bom to v2026.05.00 ([#94](https://github.com/yschimke/homeassistant-remotecompose/issues/94)) ([b941e0c](https://github.com/yschimke/homeassistant-remotecompose/commit/b941e0c8a2b880a6142f0fe3b798d6960984a4d7))
* **deps:** update dependency androidx.tv:tv-material to v1.1.0 ([#95](https://github.com/yschimke/homeassistant-remotecompose/issues/95)) ([13d5d31](https://github.com/yschimke/homeassistant-remotecompose/commit/13d5d31d76a21445df36d8a339a2e695a5ea1a3a))
* **deps:** update dependency org.hamcrest:hamcrest to v3 ([#96](https://github.com/yschimke/homeassistant-remotecompose/issues/96)) ([6f85ab1](https://github.com/yschimke/homeassistant-remotecompose/commit/6f85ab14f3787637ebbaaded29af8d7f2875401c))
* **deps:** update remote-compose ([#93](https://github.com/yschimke/homeassistant-remotecompose/issues/93)) ([29fe027](https://github.com/yschimke/homeassistant-remotecompose/commit/29fe027beddfbab60fd0739ecf776e7e7f602a68))
* **garage-card:** anchor door to top so it opens upward ([#117](https://github.com/yschimke/homeassistant-remotecompose/issues/117)) ([d5e4546](https://github.com/yschimke/homeassistant-remotecompose/commit/d5e4546bc6900141c7b9c5d6196109e49bab9406))
* **picture-elements:** overlay elements at configured x/y positions ([#114](https://github.com/yschimke/homeassistant-remotecompose/issues/114)) ([3f44bf8](https://github.com/yschimke/homeassistant-remotecompose/commit/3f44bf8cb2c0f12bace5ca2bfcb723142c3e9713)), closes [#105](https://github.com/yschimke/homeassistant-remotecompose/issues/105)
* **previews:** freeze clock card time so preview diffs are stable ([#113](https://github.com/yschimke/homeassistant-remotecompose/issues/113)) ([302b402](https://github.com/yschimke/homeassistant-remotecompose/commit/302b40271296342b4a7bc062d17174582b4fb8d0))
* **rc-image-coil:** handle hardware bitmaps in memory-cache hits ([#127](https://github.com/yschimke/homeassistant-remotecompose/issues/127)) ([709c45c](https://github.com/yschimke/homeassistant-remotecompose/commit/709c45c340ce359b24a13537bfce6d8f10068a3d))
* **rc:** size CachedCardPreview to its slot so cards render ([#129](https://github.com/yschimke/homeassistant-remotecompose/issues/129)) ([2531fba](https://github.com/yschimke/homeassistant-remotecompose/commit/2531fbaac86ad5270552d767c15f72dac81f577b))

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
