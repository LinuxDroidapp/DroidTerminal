#ifndef DROIDTERMINAL_PTY_INTERNAL_H
#define DROIDTERMINAL_PTY_INTERNAL_H

#include <sys/types.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>
#include <fcntl.h>
#include <pty.h>
#include <errno.h>

#include <string>
#include <vector>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <atomic>

#ifdef __ANDROID__
#include <android/log.h>
#define LOG_TAG "DroidTermNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
#include <cstdio>
#define LOGI(...) do { printf("[INFO] " __VA_ARGS__); printf("\n"); } while(0)
#define LOGW(...) do { printf("[WARN] " __VA_ARGS__); printf("\n"); } while(0)
#define LOGE(...) do { fprintf(stderr, "[ERROR] " __VA_ARGS__); fprintf(stderr, "\n"); } while(0)
#endif

namespace droidterm {

struct SessionConfig {
    std::string rootfsPath;
    std::string prootBinPath;
    std::string executable;      // e.g., /bin/bash or /bin/zsh
    std::vector<std::string> args;
    std::string workingDir;
    std::vector<std::pair<std::string, std::string>> envVars;
    std::vector<std::pair<std::string, std::string>> bindMounts;
    bool isProot;
    int cols;
    int rows;
};

struct TerminalSessionInstance {
    int sessionId;
    int masterFd;
    pid_t childPid;
    std::atomic<bool> isAlive;
    std::atomic<int> exitStatus;
    std::mutex sessionMutex;
    unsigned short currentCols;
    unsigned short currentRows;

    TerminalSessionInstance()
        : sessionId(-1), masterFd(-1), childPid(-1),
          isAlive(false), exitStatus(-1), currentCols(80), currentRows(24) {}

    ~TerminalSessionInstance() {
        closeMaster();
    }

    void closeMaster() {
        std::lock_guard<std::mutex> lock(sessionMutex);
        if (masterFd >= 0) {
            close(masterFd);
            masterFd = -1;
        }
    }
};

class SessionRegistry {
public:
    static SessionRegistry& getInstance() {
        static SessionRegistry instance;
        return instance;
    }

    int registerSession(std::shared_ptr<TerminalSessionInstance> session);
    std::shared_ptr<TerminalSessionInstance> getSession(int sessionId);
    void unregisterSession(int sessionId);
    size_t getActiveSessionCount();

private:
    SessionRegistry() : nextSessionId_(1) {}
    std::mutex registryMutex_;
    int nextSessionId_;
    std::unordered_map<int, std::shared_ptr<TerminalSessionInstance>> sessions_;
};

// Process management APIs
int spawnPtyProcess(const SessionConfig& config, std::shared_ptr<TerminalSessionInstance>& outSession);
int checkProcessExit(TerminalSessionInstance* session);
int setPtyWindowSize(int masterFd, int cols, int rows);
ssize_t readPtyNonBlocking(int masterFd, char* buffer, size_t length);
ssize_t writePtyAll(int masterFd, const char* buffer, size_t length);
int sendPtySignal(TerminalSessionInstance* session, int signalNumber);

} // namespace droidterm

#endif // DROIDTERMINAL_PTY_INTERNAL_H
