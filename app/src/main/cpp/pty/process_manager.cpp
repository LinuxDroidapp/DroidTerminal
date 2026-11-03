#include "pty_internal.h"
#include <cstring>
#include <cstdlib>
#include <signal.h>
#include <sys/stat.h>

namespace droidterm {

int SessionRegistry::registerSession(std::shared_ptr<TerminalSessionInstance> session) {
    std::lock_guard<std::mutex> lock(registryMutex_);
    int id = nextSessionId_++;
    session->sessionId = id;
    sessions_[id] = session;
    return id;
}

std::shared_ptr<TerminalSessionInstance> SessionRegistry::getSession(int sessionId) {
    std::lock_guard<std::mutex> lock(registryMutex_);
    auto it = sessions_.find(sessionId);
    if (it != sessions_.end()) {
        return it->second;
    }
    return nullptr;
}

void SessionRegistry::unregisterSession(int sessionId) {
    std::lock_guard<std::mutex> lock(registryMutex_);
    sessions_.erase(sessionId);
}

size_t SessionRegistry::getActiveSessionCount() {
    std::lock_guard<std::mutex> lock(registryMutex_);
    return sessions_.size();
}

int spawnPtyProcess(const SessionConfig& config, std::shared_ptr<TerminalSessionInstance>& outSession) {
    int masterFd = -1;
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_col = static_cast<unsigned short>(config.cols > 0 ? config.cols : 80);
    ws.ws_row = static_cast<unsigned short>(config.rows > 0 ? config.rows : 24);

    struct termios tios;
    memset(&tios, 0, sizeof(tios));
    tios.c_iflag = ICRNL | IXON;
    tios.c_oflag = OPOST | ONLCR;
    tios.c_cflag = CS8 | CREAD;
    tios.c_lflag = ISIG | ICANON | ECHO | ECHOE | ECHOK | ECHOCTL | ECHOKE | IEXTEN;
    tios.c_cc[VINTR]  = 0x03; // Ctrl+C
    tios.c_cc[VQUIT]  = 0x1C; // Ctrl+backslash
    tios.c_cc[VERASE] = 0x7F; // Backspace
    tios.c_cc[VKILL]  = 0x15; // Ctrl+U
    tios.c_cc[VEOF]   = 0x04; // Ctrl+D
    tios.c_cc[VSTART] = 0x11; // Ctrl+Q
    tios.c_cc[VSTOP]  = 0x13; // Ctrl+S
    tios.c_cc[VSUSP]  = 0x1A; // Ctrl+Z
    tios.c_cc[VREPRINT] = 0x12; // Ctrl+R
    tios.c_cc[VWERASE] = 0x17; // Ctrl+W
    tios.c_cc[VLNEXT] = 0x16; // Ctrl+V
    cfsetispeed(&tios, B38400);
    cfsetospeed(&tios, B38400);

    pid_t pid = forkpty(&masterFd, nullptr, &tios, &ws);
    if (pid < 0) {
        LOGE("forkpty failed with errno=%d (%s)", errno, strerror(errno));
        return -1;
    }

    if (pid == 0) {
        // Child Process
        sigset_t set;
        sigemptyset(&set);
        sigprocmask(SIG_SETMASK, &set, nullptr);

        signal(SIGINT, SIG_DFL);
        signal(SIGQUIT, SIG_DFL);
        signal(SIGTSTP, SIG_DFL);
        signal(SIGTTIN, SIG_DFL);
        signal(SIGTTOU, SIG_DFL);
        signal(SIGCHLD, SIG_DFL);

        // Problem B: Strictly purge Android host library leakage
        unsetenv("LD_PRELOAD");
        unsetenv("LD_LIBRARY_PATH");

        setenv("TERM", "xterm-256color", 1);
        setenv("COLORTERM", "truecolor", 1);
        setenv("LANG", "en_US.UTF-8", 1);
        setenv("LC_ALL", "en_US.UTF-8", 1);

        for (const auto& envPair : config.envVars) {
            if (envPair.first != "LD_PRELOAD" && envPair.first != "LD_LIBRARY_PATH") {
                setenv(envPair.first.c_str(), envPair.second.c_str(), 1);
            }
        }

        std::vector<std::string> argStrings;
        if (config.isProot && !config.prootBinPath.empty() && !config.rootfsPath.empty()) {
            // Problem B: Auto-detect and configure PROOT_LOADER if not explicitly provided
            // Resolves carrier libproot_loader.so first (SELinux apk_data_file execution compliant)
            if (getenv("PROOT_LOADER") == nullptr) {
                char resolvedReal[PATH_MAX];
                if (realpath(config.prootBinPath.c_str(), resolvedReal) != nullptr) {
                    std::string realBin = resolvedReal;
                    size_t rSlash = realBin.find_last_of('/');
                    if (rSlash != std::string::npos) {
                        std::string carrierDir = realBin.substr(0, rSlash);
                        std::string carrierLoader = carrierDir + "/libproot_loader.so";
                        if (access(carrierLoader.c_str(), R_OK) == 0) {
                            setenv("PROOT_LOADER", carrierLoader.c_str(), 1);
                        }
                    }
                }
            }

            if (getenv("PROOT_LOADER") == nullptr) {
                std::string prootDir;
                size_t slashPos = config.prootBinPath.find_last_of('/');
                if (slashPos != std::string::npos) {
                    prootDir = config.prootBinPath.substr(0, slashPos);
                }
                std::string soLoader = prootDir + "/libproot_loader.so";
                if (access(soLoader.c_str(), R_OK) == 0) {
                    setenv("PROOT_LOADER", soLoader.c_str(), 1);
                } else {
                    std::string loaderCandidate = prootDir + "/loader";
                    if (access(loaderCandidate.c_str(), X_OK) == 0) {
                        setenv("PROOT_LOADER", loaderCandidate.c_str(), 1);
                    }
                }
            }

            // Problem C: Dedicated host temporary directory configuration
            if (getenv("PROOT_TMP_DIR") == nullptr) {
                std::string rootfsParent;
                size_t slashPos = config.rootfsPath.find_last_of('/');
                if (slashPos != std::string::npos) {
                    rootfsParent = config.rootfsPath.substr(0, slashPos);
                    std::string tmpCandidate = rootfsParent + "/tmp";
                    mkdir(tmpCandidate.c_str(), 0777);
                    chmod(tmpCandidate.c_str(), 0777);
                    if (access(tmpCandidate.c_str(), W_OK) == 0) {
                        setenv("PROOT_TMP_DIR", tmpCandidate.c_str(), 1);
                    }
                }
            } else {
                const char* curTmp = getenv("PROOT_TMP_DIR");
                if (curTmp != nullptr) {
                    mkdir(curTmp, 0777);
                    chmod(curTmp, 0777);
                }
            }

            argStrings.push_back(config.prootBinPath);
            argStrings.push_back("--kill-on-exit");
            argStrings.push_back("--link2symlink");
            argStrings.push_back("-0");
            argStrings.push_back("-r");
            argStrings.push_back(config.rootfsPath);

            argStrings.push_back("-b");
            argStrings.push_back("/dev");
            argStrings.push_back("-b");
            argStrings.push_back("/proc");
            argStrings.push_back("-b");
            argStrings.push_back("/sys");

            for (const auto& bind : config.bindMounts) {
                argStrings.push_back("-b");
                if (bind.second.empty() || bind.first == bind.second) {
                    argStrings.push_back(bind.first);
                } else {
                    argStrings.push_back(bind.first + ":" + bind.second);
                }
            }

            // Problem A: Guest working directory normalization (never pass host path)
            std::string guestWd = config.workingDir.empty() ? "/root" : config.workingDir;
            if (!config.rootfsPath.empty() && guestWd.rfind(config.rootfsPath, 0) == 0) {
                guestWd = guestWd.substr(config.rootfsPath.length());
            }
            if (guestWd.empty() || guestWd[0] != '/') {
                guestWd = "/" + guestWd;
            }
            std::string fullWd = config.rootfsPath + guestWd;
            if (access(fullWd.c_str(), F_OK) != 0) {
                if (guestWd == "/root" || guestWd == "/tmp" || guestWd == "/home") {
                    mkdir(fullWd.c_str(), 0755);
                } else {
                    guestWd = "/";
                }
            }
            argStrings.push_back("-w");
            argStrings.push_back(guestWd);

            std::string shellExe = config.executable.empty() ? "/bin/bash" : config.executable;
            argStrings.push_back(shellExe);
            if (config.args.empty()) {
                argStrings.push_back("-l");
            } else {
                for (const auto& a : config.args) {
                    argStrings.push_back(a);
                }
            }
        } else {
            std::string exe = config.executable.empty() ? "/bin/sh" : config.executable;
            argStrings.push_back(exe);
            if (config.args.empty()) {
                argStrings.push_back("-i");
            } else {
                for (const auto& a : config.args) {
                    argStrings.push_back(a);
                }
            }
            if (!config.workingDir.empty()) {
                chdir(config.workingDir.c_str());
            }
        }

        std::vector<char*> cArgs;
        for (const auto& s : argStrings) {
            cArgs.push_back(const_cast<char*>(s.c_str()));
        }
        cArgs.push_back(nullptr);

        LOGI("Child executing: %s", cArgs[0]);
        execvp(cArgs[0], cArgs.data());

        LOGE("execvp failed for %s: errno=%d (%s)", cArgs[0], errno, strerror(errno));
        _exit(127);
    }

    int flags = fcntl(masterFd, F_GETFL, 0);
    if (flags >= 0) {
        fcntl(masterFd, F_SETFL, flags | O_NONBLOCK);
    }

    outSession = std::make_shared<TerminalSessionInstance>();
    outSession->masterFd = masterFd;
    outSession->childPid = pid;
    outSession->isAlive = true;
    outSession->currentCols = ws.ws_col;
    outSession->currentRows = ws.ws_row;

    LOGI("Pty allocated: masterFd=%d, childPid=%d", masterFd, pid);
    return 0;
}

int checkProcessExit(TerminalSessionInstance* session) {
    if (!session || !session->isAlive.load()) return -1;

    int status = 0;
    pid_t result = waitpid(session->childPid, &status, WNOHANG);
    if (result == session->childPid) {
        session->isAlive.store(false);
        int exitCode = WIFEXITED(status) ? WEXITSTATUS(status) : (WIFSIGNALED(status) ? 128 + WTERMSIG(status) : -1);
        session->exitStatus.store(exitCode);
        LOGI("Process pid=%d terminated with status=%d", session->childPid, exitCode);
        return exitCode;
    } else if (result < 0 && errno == ECHILD) {
        session->isAlive.store(false);
        return session->exitStatus.load();
    }
    return -1;
}

int sendPtySignal(TerminalSessionInstance* session, int signalNumber) {
    if (!session || !session->isAlive.load() || session->childPid <= 0) {
        return -1;
    }
    return kill(session->childPid, signalNumber);
}

} // namespace droidterm
