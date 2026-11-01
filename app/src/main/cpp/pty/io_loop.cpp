#include "pty_internal.h"
#include <poll.h>

namespace droidterm {

ssize_t readPtyNonBlocking(int masterFd, char* buffer, size_t length) {
    if (masterFd < 0 || !buffer || length == 0) {
        return -1;
    }

    struct pollfd pfd;
    pfd.fd = masterFd;
    pfd.events = POLLIN;
    pfd.revents = 0;

    int pollRet = poll(&pfd, 1, 0); // Non-blocking check
    if (pollRet < 0) {
        if (errno == EINTR) return 0;
        return -1;
    }
    if (pollRet == 0) {
        return 0; // No data available right now
    }

    // Notice: Do NOT immediately abort on POLLHUP. If the child process printed text
    // and exited, POLLIN and POLLHUP are both set. We must read out remaining buffered data.
    ssize_t bytesRead = read(masterFd, buffer, length);
    if (bytesRead < 0) {
        if (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) {
            return 0;
        }
        if (errno == EIO) {
            // EIO on Linux master PTY signifies that slave was closed (process exited and buffer empty)
            return -1;
        }
        return -1;
    }
    if (bytesRead == 0) {
        // EOF
        return -1;
    }
    return bytesRead;
}

ssize_t writePtyAll(int masterFd, const char* buffer, size_t length) {
    if (masterFd < 0 || !buffer || length == 0) {
        return -1;
    }

    size_t totalWritten = 0;
    while (totalWritten < length) {
        ssize_t written = write(masterFd, buffer + totalWritten, length - totalWritten);
        if (written < 0) {
            if (errno == EINTR) {
                continue;
            }
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                struct pollfd pfd;
                pfd.fd = masterFd;
                pfd.events = POLLOUT;
                pfd.revents = 0;
                poll(&pfd, 1, 50); // 50ms wait
                continue;
            }
            return -1;
        }
        totalWritten += static_cast<size_t>(written);
    }
    return static_cast<ssize_t>(totalWritten);
}

} // namespace droidterm
