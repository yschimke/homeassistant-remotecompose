#pragma once
// The lwIP sockets open now: how many, and optionally what each is ("6053<-192.168.0.141" for a
// client of the device's own servers, "->192.168.0.212:8123" for a request of its own, "listen
// 3232", "udp 32768"). For logging what holds them when CONFIG_LWIP_MAX_SOCKETS runs out, which
// shows up as requests that can't reach Home Assistant.

#include <initializer_list>
#include <string>

namespace tzwall {

// [server_ports]: ports the device serves on (ESPHome's API 6053, OTA 3232, ...), whose
// connections are listed as clients of it rather than requests of its own.
int open_sockets(std::string *list = nullptr, std::initializer_list<unsigned> server_ports = {6053, 3232, 80});

}  // namespace tzwall
