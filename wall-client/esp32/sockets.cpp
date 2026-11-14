#include "sockets.h"

#include <algorithm>
#include <cstdio>

#include "lwip/sockets.h"
#include "sdkconfig.h"

namespace tzwall {

int open_sockets(std::string *list, std::initializer_list<unsigned> server_ports) {
  int n = 0;
  for (int fd = LWIP_SOCKET_OFFSET; fd < LWIP_SOCKET_OFFSET + CONFIG_LWIP_MAX_SOCKETS; fd++) {
    int type = 0;
    socklen_t len = sizeof type;
    if (getsockopt(fd, SOL_SOCKET, SO_TYPE, &type, &len) != 0) continue;
    n++;
    if (list == nullptr) continue;
    sockaddr_in self = {}, peer = {};
    socklen_t sl = sizeof self, pl = sizeof peer;
    getsockname(fd, reinterpret_cast<sockaddr *>(&self), &sl);
    char ip[16] = "";
    const bool connected = getpeername(fd, reinterpret_cast<sockaddr *>(&peer), &pl) == 0;
    if (connected) inet_ntoa_r(peer.sin_addr, ip, sizeof ip);
    const unsigned port = ntohs(self.sin_port), to = ntohs(peer.sin_port);
    const bool served = std::find(server_ports.begin(), server_ports.end(), port) != server_ports.end();
    char one[48];
    if (type != SOCK_STREAM)
      snprintf(one, sizeof one, "udp %u", port);
    else if (!connected)
      snprintf(one, sizeof one, "listen %u", port);
    else if (served)
      snprintf(one, sizeof one, "%u<-%s", port, ip);
    else
      snprintf(one, sizeof one, "->%s:%u", ip, to);
    *list += (list->empty() ? "" : ", ") + std::string(one);
  }
  return n;
}

}  // namespace tzwall
