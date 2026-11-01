#include "pty_internal.h"
#include <cstring>
#include <cerrno>

namespace droidterm {

int setPtyWindowSize(int masterFd, int cols, int rows) {
    if (masterFd < 0 || cols <= 0 || rows <= 0) {
        return -1;
    }

    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_col = static_cast<unsigned short>(cols);
    ws.ws_row = static_cast<unsigned short>(rows);
    ws.ws_xpixel = 0;
    ws.ws_ypixel = 0;

    int res = ioctl(masterFd, TIOCSWINSZ, &ws);
    if (res < 0) {
        LOGW("ioctl TIOCSWINSZ failed: errno=%d (%s)", errno, strerror(errno));
        return -1;
    }
    LOGI("Pty resized to %dx%d", cols, rows);
    return 0;
}

} // namespace droidterm
