#include "pty_internal.h"
#include <jni.h>

using namespace droidterm;

static std::string jstringToString(JNIEnv *env, jstring jstr) {
    if (!jstr) return "";
    const char *chars = env->GetStringUTFChars(jstr, nullptr);
    std::string str = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(jstr, chars);
    return str;
}

extern "C" {

JNIEXPORT jint JNICALL
Java_com_droidterminal_runtime_NativePtyBridge_nativeCreateSession(
        JNIEnv *env,
        jobject thiz,
        jstring rootfsPath,
        jstring prootBinPath,
        jstring executable,
        jobjectArray argsArray,
        jstring workingDir,
        jobjectArray envKeysArray,
        jobjectArray envValuesArray,
        jobjectArray bindSourcesArray,
        jobjectArray bindTargetsArray,
        jboolean isProot,
        jint cols,
        jint rows) {

    SessionConfig config;
    config.rootfsPath = jstringToString(env, rootfsPath);
    config.prootBinPath = jstringToString(env, prootBinPath);
    config.executable = jstringToString(env, executable);
    config.workingDir = jstringToString(env, workingDir);
    config.isProot = static_cast<bool>(isProot);
    config.cols = cols;
    config.rows = rows;

    if (argsArray) {
        jsize len = env->GetArrayLength(argsArray);
        for (jsize i = 0; i < len; ++i) {
            auto jstr = (jstring)env->GetObjectArrayElement(argsArray, i);
            config.args.push_back(jstringToString(env, jstr));
            env->DeleteLocalRef(jstr);
        }
    }

    if (envKeysArray && envValuesArray) {
        jsize len = env->GetArrayLength(envKeysArray);
        for (jsize i = 0; i < len; ++i) {
            auto k = (jstring)env->GetObjectArrayElement(envKeysArray, i);
            auto v = (jstring)env->GetObjectArrayElement(envValuesArray, i);
            config.envVars.push_back({jstringToString(env, k), jstringToString(env, v)});
            env->DeleteLocalRef(k);
            env->DeleteLocalRef(v);
        }
    }

    if (bindSourcesArray && bindTargetsArray) {
        jsize len = env->GetArrayLength(bindSourcesArray);
        for (jsize i = 0; i < len; ++i) {
            auto s = (jstring)env->GetObjectArrayElement(bindSourcesArray, i);
            auto t = (jstring)env->GetObjectArrayElement(bindTargetsArray, i);
            config.bindMounts.push_back({jstringToString(env, s), jstringToString(env, t)});
            env->DeleteLocalRef(s);
            env->DeleteLocalRef(t);
        }
    }

    std::shared_ptr<TerminalSessionInstance> session;
    int res = spawnPtyProcess(config, session);
    if (res != 0 || !session) {
        LOGE("Failed to spawn PTY session");
        return -1;
    }

    int sessionId = SessionRegistry::getInstance().registerSession(session);
    LOGI("Session registered with ID: %d", sessionId);
    return sessionId;
}

JNIEXPORT jint JNICALL
Java_com_droidterminal_runtime_NativePtyBridge_nativeReadSession(
        JNIEnv *env,
        jobject thiz,
        jint sessionId,
        jbyteArray buffer,
        jint offset,
        jint length) {

    auto session = SessionRegistry::getInstance().getSession(sessionId);
    if (!session || session->masterFd < 0) {
        return -1;
    }

    checkProcessExit(session.get());

    jbyte *bufPtr = env->GetByteArrayElements(buffer, nullptr);
    if (!bufPtr) return -1;

    ssize_t bytesRead = readPtyNonBlocking(session->masterFd, reinterpret_cast<char*>(bufPtr + offset), length);
    env->ReleaseByteArrayElements(buffer, bufPtr, 0);

    if (bytesRead < 0) {
        checkProcessExit(session.get());
    }

    return static_cast<jint>(bytesRead);
}

JNIEXPORT jint JNICALL
Java_com_droidterminal_runtime_NativePtyBridge_nativeWriteSession(
        JNIEnv *env,
        jobject thiz,
        jint sessionId,
        jbyteArray buffer,
        jint offset,
        jint length) {

    auto session = SessionRegistry::getInstance().getSession(sessionId);
    if (!session || session->masterFd < 0) {
        return -1;
    }

    jbyte *bufPtr = env->GetByteArrayElements(buffer, nullptr);
    if (!bufPtr) return -1;

    ssize_t written = writePtyAll(session->masterFd, reinterpret_cast<const char*>(bufPtr + offset), length);
    env->ReleaseByteArrayElements(buffer, bufPtr, JNI_ABORT);

    return static_cast<jint>(written);
}

JNIEXPORT jboolean JNICALL
Java_com_droidterminal_runtime_NativePtyBridge_nativeResizeSession(
        JNIEnv *env,
        jobject thiz,
        jint sessionId,
        jint cols,
        jint rows) {

    auto session = SessionRegistry::getInstance().getSession(sessionId);
    if (!session || session->masterFd < 0) {
        return JNI_FALSE;
    }

    int res = setPtyWindowSize(session->masterFd, cols, rows);
    if (res == 0) {
        session->currentCols = static_cast<unsigned short>(cols);
        session->currentRows = static_cast<unsigned short>(rows);
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_droidterminal_runtime_NativePtyBridge_nativeSendSignal(
        JNIEnv *env,
        jobject thiz,
        jint sessionId,
        jint signal) {

    auto session = SessionRegistry::getInstance().getSession(sessionId);
    if (!session) return JNI_FALSE;

    int res = sendPtySignal(session.get(), signal);
    return res == 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_droidterminal_runtime_NativePtyBridge_nativeCloseSession(
        JNIEnv *env,
        jobject thiz,
        jint sessionId) {

    auto session = SessionRegistry::getInstance().getSession(sessionId);
    if (session) {
        session->closeMaster();
        SessionRegistry::getInstance().unregisterSession(sessionId);
        LOGI("Session ID %d closed and unregistered", sessionId);
    }
}

JNIEXPORT jboolean JNICALL
Java_com_droidterminal_runtime_NativePtyBridge_nativeIsSessionAlive(
        JNIEnv *env,
        jobject thiz,
        jint sessionId) {

    auto session = SessionRegistry::getInstance().getSession(sessionId);
    if (!session) return JNI_FALSE;

    checkProcessExit(session.get());
    return session->isAlive.load() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_droidterminal_runtime_NativePtyBridge_nativeGetSessionExitCode(
        JNIEnv *env,
        jobject thiz,
        jint sessionId) {

    auto session = SessionRegistry::getInstance().getSession(sessionId);
    if (!session) return -1;

    checkProcessExit(session.get());
    return session->exitStatus.load();
}

} // extern "C"
