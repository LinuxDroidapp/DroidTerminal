#include "pty_internal.h"
#include <iostream>
#include <cassert>
#include <thread>
#include <chrono>

using namespace droidterm;

void testPtyBasicAllocationAndExecution() {
    std::cout << "[TEST] Running testPtyBasicAllocationAndExecution..." << std::endl;

    SessionConfig config;
    config.isProot = false;
    config.executable = "/bin/sh";
    config.args = {"-c", "echo 'DROIDTERM_PTY_TEST_PASSED'; exit 42"};
    config.cols = 80;
    config.rows = 24;

    std::shared_ptr<TerminalSessionInstance> session;
    int res = spawnPtyProcess(config, session);
    assert(res == 0 && "spawnPtyProcess must return 0");
    assert(session != nullptr && "session must not be null");
    assert(session->masterFd >= 0 && "masterFd must be valid");
    assert(session->childPid > 0 && "childPid must be valid");

    int id = SessionRegistry::getInstance().registerSession(session);
    assert(id > 0 && "Session ID must be positive");

    // Read output from PTY
    std::string accumulated;
    char buffer[256];
    int attempts = 0;
    while (attempts++ < 50) {
        ssize_t bytesRead = readPtyNonBlocking(session->masterFd, buffer, sizeof(buffer) - 1);
        if (bytesRead > 0) {
            buffer[bytesRead] = '\0';
            accumulated += buffer;
            if (accumulated.find("DROIDTERM_PTY_TEST_PASSED") != std::string::npos) {
                break;
            }
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(20));
    }

    std::cout << "  Captured PTY output: " << accumulated << std::endl;
    assert(accumulated.find("DROIDTERM_PTY_TEST_PASSED") != std::string::npos && "Output must contain test marker");

    // Wait for child to exit
    int exitCode = -1;
    for (int i = 0; i < 50; ++i) {
        exitCode = checkProcessExit(session.get());
        if (!session->isAlive.load()) break;
        std::this_thread::sleep_for(std::chrono::milliseconds(20));
    }

    std::cout << "  Child exited with code: " << exitCode << std::endl;
    assert(exitCode == 42 && "Child exit code must be 42");

    session->closeMaster();
    SessionRegistry::getInstance().unregisterSession(id);
    std::cout << "[PASS] testPtyBasicAllocationAndExecution passed!" << std::endl;
}

void testPtyBidirectionalInteractiveIO() {
    std::cout << "[TEST] Running testPtyBidirectionalInteractiveIO..." << std::endl;

    SessionConfig config;
    config.isProot = false;
    config.executable = "/bin/sh";
    config.args = {"-i"};
    config.cols = 120;
    config.rows = 40;

    std::shared_ptr<TerminalSessionInstance> session;
    int res = spawnPtyProcess(config, session);
    assert(res == 0);

    // Test resize
    int resizeRes = setPtyWindowSize(session->masterFd, 100, 30);
    assert(resizeRes == 0 && "Resize must succeed");

    // Send input command
    std::string cmd = "expr 1234 + 5678\n";
    ssize_t written = writePtyAll(session->masterFd, cmd.c_str(), cmd.length());
    assert(written == (ssize_t)cmd.length());

    std::string output;
    char buffer[256];
    int attempts = 0;
    while (attempts++ < 50) {
        ssize_t n = readPtyNonBlocking(session->masterFd, buffer, sizeof(buffer) - 1);
        if (n > 0) {
            buffer[n] = '\0';
            output += buffer;
            if (output.find("6912") != std::string::npos) {
                break;
            }
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(20));
    }

    std::cout << "  Captured interactive calculation output: " << output << std::endl;
    assert(output.find("6912") != std::string::npos && "Calculation result must be 6912");

    // Terminate session
    std::string exitCmd = "exit\n";
    writePtyAll(session->masterFd, exitCmd.c_str(), exitCmd.length());
    std::this_thread::sleep_for(std::chrono::milliseconds(50));
    session->closeMaster();

    std::cout << "[PASS] testPtyBidirectionalInteractiveIO passed!" << std::endl;
}

void testMultipleConcurrentPtySessions() {
    std::cout << "[TEST] Running testMultipleConcurrentPtySessions..." << std::endl;

    const int SESSIONS = 4;
    std::vector<std::shared_ptr<TerminalSessionInstance>> list;

    for (int i = 0; i < SESSIONS; ++i) {
        SessionConfig cfg;
        cfg.isProot = false;
        cfg.executable = "/bin/sh";
        cfg.args = {"-c", "echo 'SESSION_" + std::to_string(i) + "'; sleep 0.1"};
        cfg.cols = 80;
        cfg.rows = 24;

        std::shared_ptr<TerminalSessionInstance> sess;
        int r = spawnPtyProcess(cfg, sess);
        assert(r == 0);
        SessionRegistry::getInstance().registerSession(sess);
        list.push_back(sess);
    }

    assert(SessionRegistry::getInstance().getActiveSessionCount() >= SESSIONS);

    for (int i = 0; i < SESSIONS; ++i) {
        char buf[256];
        std::string out;
        for (int a = 0; a < 30; ++a) {
            ssize_t n = readPtyNonBlocking(list[i]->masterFd, buf, sizeof(buf) - 1);
            if (n > 0) {
                buf[n] = '\0';
                out += buf;
            }
            if (out.find("SESSION_" + std::to_string(i)) != std::string::npos) break;
            std::this_thread::sleep_for(std::chrono::milliseconds(20));
        }
        assert(out.find("SESSION_" + std::to_string(i)) != std::string::npos);
        list[i]->closeMaster();
        SessionRegistry::getInstance().unregisterSession(list[i]->sessionId);
    }

    std::cout << "[PASS] testMultipleConcurrentPtySessions passed!" << std::endl;
}

int main() {
    std::cout << "============================================" << std::endl;
    std::cout << "Starting DroidTerminal Native PTY Engine Tests" << std::endl;
    std::cout << "============================================" << std::endl;

    testPtyBasicAllocationAndExecution();
    testPtyBidirectionalInteractiveIO();
    testMultipleConcurrentPtySessions();

    std::cout << "============================================" << std::endl;
    std::cout << "ALL NATIVE PTY TESTS PASSED SUCCESSFULLY!" << std::endl;
    std::cout << "============================================" << std::endl;
    return 0;
}
