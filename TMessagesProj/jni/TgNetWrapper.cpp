#include <jni.h>
#include <string>
#include <chrono>

JavaVM *java;

// Stub class references (no longer used but kept for JNI registration)
jclass jclass_RequestTimeDelegate;
jmethodID jclass_RequestTimeDelegate_run;

jclass jclass_ConnectionsManager;
jmethodID jclass_ConnectionsManager_onRequestClear;
jmethodID jclass_ConnectionsManager_onRequestComplete;
jmethodID jclass_ConnectionsManager_onRequestQuickAck;
jmethodID jclass_ConnectionsManager_onRequestWriteToSocket;
jmethodID jclass_ConnectionsManager_onUnparsedMessageReceived;
jmethodID jclass_ConnectionsManager_onUpdate;
jmethodID jclass_ConnectionsManager_onSessionCreated;
jmethodID jclass_ConnectionsManager_onLogout;
jmethodID jclass_ConnectionsManager_onConnectionStateChanged;
jmethodID jclass_ConnectionsManager_onInternalPushReceived;
jmethodID jclass_ConnectionsManager_onUpdateConfig;
jmethodID jclass_ConnectionsManager_onBytesSent;
jmethodID jclass_ConnectionsManager_onBytesReceived;
jmethodID jclass_ConnectionsManager_onRequestNewServerIpAndPort;
jmethodID jclass_ConnectionsManager_onProxyError;
jmethodID jclass_ConnectionsManager_getHostByName;
jmethodID jclass_ConnectionsManager_getInitFlags;
jmethodID jclass_ConnectionsManager_onPremiumFloodWait;
jmethodID jclass_ConnectionsManager_onIntegrityCheckClassic;
jmethodID jclass_ConnectionsManager_onCaptchaCheck;

// DEBUG_REF macro replacement
#define DEBUG_REF(x) do {} while(0)

// ============================================================
// 🎭 STUB IMPLEMENTATIONS - All MTProto native calls are no-ops
// ============================================================

jlong getFreeBuffer(JNIEnv *env, jclass c, jint length) {
    return 0;
}

jint limit(JNIEnv *env, jclass c, jlong address) {
    return 0;
}

jint position(JNIEnv *env, jclass c, jlong address) {
    return 0;
}

void reuse(JNIEnv *env, jclass c, jlong address) {
}

jobject getJavaByteBuffer(JNIEnv *env, jclass c, jlong address) {
    return nullptr;
}

static const char *NativeByteBufferClassPathName = "com/creanger/app/tgnet/NativeByteBuffer";
static JNINativeMethod NativeByteBufferMethods[] = {
        {"native_getFreeBuffer", "(I)J", (void *) getFreeBuffer},
        {"native_limit", "(J)I", (void *) limit},
        {"native_position", "(J)I", (void *) position},
        {"native_reuse", "(J)V", (void *) reuse},
        {"native_getJavaByteBuffer", "(J)Ljava/nio/ByteBuffer;", (void *) getJavaByteBuffer}
};

jlong getCurrentTimeMillis(JNIEnv *env, jclass c, jint instanceNum) {
    return (jlong)std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()).count();
}

jint getCurrentTime(JNIEnv *env, jclass c, jint instanceNum) {
    return (int)(std::chrono::duration_cast<std::chrono::seconds>(
        std::chrono::system_clock::now().time_since_epoch()).count());
}

jint getCurrentPingTime(JNIEnv *env, jclass c, jint instanceNum) {
    return 0;
}

jint getCurrentDatacenterId(JNIEnv *env, jclass c, jint instanceNum) {
    return 0x7FFFFFFF; // DEFAULT_DATACENTER_ID
}

jlong getCurrentAuthKeyId(JNIEnv *env, jclass c, jint instanceNum) {
    return 0;
}

jint isTestBackend(JNIEnv *env, jclass c, jint instanceNum) {
    return 0;
}

jint getTimeDifference(JNIEnv *env, jclass c, jint instanceNum) {
    return 0;
}

void sendRequest(JNIEnv *env, jclass c, jint instanceNum, jlong object, jint flags, jint datacenterId, jint connectionType, jboolean immediate, jint token) {
    // STUB: No MTProto - request handled by Java CustomNetworkInterface
}

void cancelRequest(JNIEnv *env, jclass c, jint instanceNum, jint token, jboolean notifyServer) {
}

void failNotRunningRequest(JNIEnv *env, jclass c, jint instanceNum, jint token) {
}

void receivedIntegrityCheckClassic(JNIEnv *env, jclass c, jint instanceNum, jint requestToken, jstring nonce, jstring token) {
}

void receivedCaptchaResult(JNIEnv *env, jclass c, jint instanceNum, jintArray requestTokens, jstring token) {
}

jboolean isGoodPrime(JNIEnv *env, jclass c, jbyteArray prime, jint g) {
    return false;
}

void cleanUp(JNIEnv *env, jclass c, jint instanceNum, jboolean resetKeys) {
}

void cancelRequestsForGuid(JNIEnv *env, jclass c, jint instanceNum, jint guid) {
}

void bindRequestToGuid(JNIEnv *env, jclass c, jint instanceNum, jint requestToken, jint guid) {
}

void applyDatacenterAddress(JNIEnv *env, jclass c, jint instanceNum, jint datacenterId, jstring ipAddress, jint port) {
}

void setProxySettings(JNIEnv *env, jclass c, jint instanceNum, jstring address, jint port, jstring username, jstring password, jstring secret) {
}

jint getConnectionState(JNIEnv *env, jclass c, jint instanceNum) {
    return 3; // ConnectionStateConnected
}

void setUserId(JNIEnv *env, jclass c, jint instanceNum, int64_t id) {
}

void setUserPremium(JNIEnv *env, jclass c, jint instanceNum, bool premium) {
}

void switchBackend(JNIEnv *env, jclass c, jint instanceNum, jboolean restart) {
}

void pauseNetwork(JNIEnv *env, jclass c, jint instanceNum) {
}

void resumeNetwork(JNIEnv *env, jclass c, jint instanceNum, jboolean partial) {
}

void updateDcSettings(JNIEnv *env, jclass c, jint instanceNum) {
}

void moveDatacenter(JNIEnv *env, jclass c, jint instanceNum, jint datacenterId) {
}

void setIpStrategy(JNIEnv *env, jclass c, jint instanceNum, jbyte value) {
}

void setNetworkAvailable(JNIEnv *env, jclass c, jint instanceNum, jboolean value, jint networkType, jboolean slow) {
}

void setPushConnectionEnabled(JNIEnv *env, jclass c, jint instanceNum, jboolean value) {
}

void applyDnsConfig(JNIEnv *env, jclass c, jint instanceNum, jlong address, jstring phone, jint date) {
}

jlong checkProxy(JNIEnv *env, jclass c, jint instanceNum, jstring address, jint port, jstring username, jstring password, jstring secret, jobject requestTimeFunc) {
    return 0;
}

void onHostNameResolved(JNIEnv *env, jclass c, jstring host, jlong address, jstring ip) {
}

void discardConnection(JNIEnv *env, jclass c, jint instanceNum, jint datacenerId, jint connectionType) {
}

void setLangCode(JNIEnv *env, jclass c, jint instanceNum, jstring langCode) {
}

void setRegId(JNIEnv *env, jclass c, jint instanceNum, jstring regId) {
}

void setSystemLangCode(JNIEnv *env, jclass c, jint instanceNum, jstring langCode) {
}

void init(JNIEnv *env, jclass c, jint instanceNum, jint version, jint layer, jint apiId, jstring deviceModel, jstring systemVersion, jstring appVersion, jstring langCode, jstring systemLangCode, jstring configPath, jstring logPath, jstring regId, jstring cFingerprint, jstring installerId, jstring packageId, jint timezoneOffset, jlong userId, jboolean userPremium, jboolean enablePushConnection, jboolean hasNetwork, jint networkType, jint performanceClass) {
}

void setJava(JNIEnv *env, jclass c, jboolean useJavaByteBuffers) {
}

static const char *ConnectionsManagerClassPathName = "com/creanger/app/tgnet/ConnectionsManager";
static JNINativeMethod ConnectionsManagerMethods[] = {
        {"native_getCurrentTimeMillis", "(I)J", (void *) getCurrentTimeMillis},
        {"native_getCurrentTime", "(I)I", (void *) getCurrentTime},
        {"native_getCurrentPingTime", "(I)I", (void *) getCurrentPingTime},
        {"native_getCurrentDatacenterId", "(I)I", (void *) getCurrentDatacenterId},
        {"native_getCurrentAuthKeyId", "(I)J", (void *) getCurrentAuthKeyId},
        {"native_isTestBackend", "(I)I", (void *) isTestBackend},
        {"native_getTimeDifference", "(I)I", (void *) getTimeDifference},
        {"native_sendRequest", "(IJIIIZI)V", (void *) sendRequest},
        {"native_cancelRequest", "(IIZ)V", (void *) cancelRequest},
        {"native_cleanUp", "(IZ)V", (void *) cleanUp},
        {"native_cancelRequestsForGuid", "(II)V", (void *) cancelRequestsForGuid},
        {"native_bindRequestToGuid", "(III)V", (void *) bindRequestToGuid},
        {"native_applyDatacenterAddress", "(IILjava/lang/String;I)V", (void *) applyDatacenterAddress},
        {"native_setProxySettings", "(ILjava/lang/String;ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V", (void *) setProxySettings},
        {"native_getConnectionState", "(I)I", (void *) getConnectionState},
        {"native_setUserId", "(IJ)V", (void *) setUserId},
        {"native_init", "(IIIILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;IJZZZII)V", (void *) init},
        {"native_setLangCode", "(ILjava/lang/String;)V", (void *) setLangCode},
        {"native_setRegId", "(ILjava/lang/String;)V", (void *) setRegId},
        {"native_setSystemLangCode", "(ILjava/lang/String;)V", (void *) setSystemLangCode},
        {"native_switchBackend", "(IZ)V", (void *) switchBackend},
        {"native_pauseNetwork", "(I)V", (void *) pauseNetwork},
        {"native_resumeNetwork", "(IZ)V", (void *) resumeNetwork},
        {"native_updateDcSettings", "(I)V", (void *) updateDcSettings},
        {"native_moveDatacenter", "(II)V", (void *) moveDatacenter},
        {"native_setIpStrategy", "(IB)V", (void *) setIpStrategy},
        {"native_setNetworkAvailable", "(IZIZ)V", (void *) setNetworkAvailable},
        {"native_setPushConnectionEnabled", "(IZ)V", (void *) setPushConnectionEnabled},
        {"native_setJava", "(Z)V", (void *) setJava},
        {"native_applyDnsConfig", "(IJLjava/lang/String;I)V", (void *) applyDnsConfig},
        {"native_checkProxy", "(ILjava/lang/String;ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Lcom/creanger/app/tgnet/RequestTimeDelegate;)J", (void *) checkProxy},
        {"native_onHostNameResolved", "(Ljava/lang/String;JLjava/lang/String;)V", (void *) onHostNameResolved},
        {"native_discardConnection", "(III)V", (void *) discardConnection},
        {"native_failNotRunningRequest", "(II)V", (void *) failNotRunningRequest},
        {"native_receivedIntegrityCheckClassic", "(IILjava/lang/String;Ljava/lang/String;)V", (void *) receivedIntegrityCheckClassic},
        {"native_receivedCaptchaResult", "(I[ILjava/lang/String;)V", (void *) receivedCaptchaResult},
        {"native_isGoodPrime", "([BI)Z", (void *) isGoodPrime},
};

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_creanger_app_tgnet_ConnectionsManager_native_1test_1AuthAuthorization(JNIEnv *env, jclass clazz, jlong address) {
    return false;
}

inline int registerNativeMethods(JNIEnv *env, const char *className, JNINativeMethod *methods, int methodsCount) {
    jclass clazz;
    clazz = env->FindClass(className);
    if (clazz == NULL) {
        return JNI_FALSE;
    }
    if (env->RegisterNatives(clazz, methods, methodsCount) < 0) {
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

extern "C" int registerNativeTgNetFunctions(JavaVM *vm, JNIEnv *env) {
    java = vm;

    if (!registerNativeMethods(env, NativeByteBufferClassPathName, NativeByteBufferMethods, sizeof(NativeByteBufferMethods) / sizeof(NativeByteBufferMethods[0]))) {
        return JNI_FALSE;
    }

    if (!registerNativeMethods(env, ConnectionsManagerClassPathName, ConnectionsManagerMethods, sizeof(ConnectionsManagerMethods) / sizeof(ConnectionsManagerMethods[0]))) {
        return JNI_FALSE;
    }

    // Register callback classes (stubs)
    DEBUG_REF("RequestTimeDelegate class");
    jclass_RequestTimeDelegate = (jclass) env->NewGlobalRef(env->FindClass("com/creanger/app/tgnet/RequestTimeDelegate"));
    if (jclass_RequestTimeDelegate == 0) {
        return JNI_FALSE;
    }
    jclass_RequestTimeDelegate_run = env->GetMethodID(jclass_RequestTimeDelegate, "run", "(J)V");
    if (jclass_RequestTimeDelegate_run == 0) {
        return JNI_FALSE;
    }

    DEBUG_REF("ConnectionsManager class");
    jclass_ConnectionsManager = (jclass) env->NewGlobalRef(env->FindClass("com/creanger/app/tgnet/ConnectionsManager"));
    if (jclass_ConnectionsManager == 0) {
        return JNI_FALSE;
    }
    jclass_ConnectionsManager_onRequestClear = env->GetStaticMethodID(jclass_ConnectionsManager, "onRequestClear", "(IIZ)V");
    jclass_ConnectionsManager_onRequestComplete = env->GetStaticMethodID(jclass_ConnectionsManager, "onRequestComplete", "(IIJILjava/lang/String;IJJI)V");
    jclass_ConnectionsManager_onRequestWriteToSocket = env->GetStaticMethodID(jclass_ConnectionsManager, "onRequestWriteToSocket", "(II)V");
    jclass_ConnectionsManager_onRequestQuickAck = env->GetStaticMethodID(jclass_ConnectionsManager, "onRequestQuickAck", "(II)V");
    jclass_ConnectionsManager_onUnparsedMessageReceived = env->GetStaticMethodID(jclass_ConnectionsManager, "onUnparsedMessageReceived", "(JIJ)V");
    jclass_ConnectionsManager_onUpdate = env->GetStaticMethodID(jclass_ConnectionsManager, "onUpdate", "(I)V");
    jclass_ConnectionsManager_onSessionCreated = env->GetStaticMethodID(jclass_ConnectionsManager, "onSessionCreated", "(I)V");
    jclass_ConnectionsManager_onLogout = env->GetStaticMethodID(jclass_ConnectionsManager, "onLogout", "(I)V");
    jclass_ConnectionsManager_onConnectionStateChanged = env->GetStaticMethodID(jclass_ConnectionsManager, "onConnectionStateChanged", "(II)V");
    jclass_ConnectionsManager_onInternalPushReceived = env->GetStaticMethodID(jclass_ConnectionsManager, "onInternalPushReceived", "(I)V");
    jclass_ConnectionsManager_onUpdateConfig = env->GetStaticMethodID(jclass_ConnectionsManager, "onUpdateConfig", "(JI)V");
    jclass_ConnectionsManager_onBytesSent = env->GetStaticMethodID(jclass_ConnectionsManager, "onBytesSent", "(III)V");
    jclass_ConnectionsManager_onBytesReceived = env->GetStaticMethodID(jclass_ConnectionsManager, "onBytesReceived", "(III)V");
    jclass_ConnectionsManager_onRequestNewServerIpAndPort = env->GetStaticMethodID(jclass_ConnectionsManager, "onRequestNewServerIpAndPort", "(II)V");
    jclass_ConnectionsManager_onProxyError = env->GetStaticMethodID(jclass_ConnectionsManager, "onProxyError", "()V");
    jclass_ConnectionsManager_getHostByName = env->GetStaticMethodID(jclass_ConnectionsManager, "getHostByName", "(Ljava/lang/String;J)V");
    jclass_ConnectionsManager_getInitFlags = env->GetStaticMethodID(jclass_ConnectionsManager, "getInitFlags", "()I");
    jclass_ConnectionsManager_onPremiumFloodWait = env->GetStaticMethodID(jclass_ConnectionsManager, "onPremiumFloodWait", "(IIZ)V");
    jclass_ConnectionsManager_onIntegrityCheckClassic = env->GetStaticMethodID(jclass_ConnectionsManager, "onIntegrityCheckClassic", "(IILjava/lang/String;Ljava/lang/String;)V");
    jclass_ConnectionsManager_onCaptchaCheck = env->GetStaticMethodID(jclass_ConnectionsManager, "onCaptchaCheck", "(IILjava/lang/String;Ljava/lang/String;)V");

    return JNI_TRUE;
}

// ============================================================
// 🔧 SHARED NATIVE UTILITIES - Stubs for removed tgnet library
// Required by: voip (FileLog), SqliteWrapper (BuffersStorage, NativeByteBuffer), gifvideo (javaVm)
// ============================================================

// javaVm global for gifvideo.cpp
JavaVM *javaVm = nullptr;

// --- FileLog stub (used by voip/tgcalls) ---
class FileLog {
public:
    static FileLog& getInstance() {
        static FileLog instance;
        return instance;
    }
    void d(const char* fmt, ...) {}
    void e(const char* fmt, ...) {}
    void w(const char* fmt, ...) {}
    void i(const char* fmt, ...) {}
    void v(const char* fmt, ...) {}
    void ref(const char* fmt, ...) {}
    void delref(const char* fmt, ...) {}
    std::string getNetworkLogPath() { return ""; }
};

// --- NativeByteBuffer stub (must match tgnet/NativeByteBuffer.h) ---
class NativeByteBuffer {
public:
    NativeByteBuffer(uint32_t size);
    NativeByteBuffer(bool calculate);
    NativeByteBuffer(uint8_t *buff, uint32_t length);
    ~NativeByteBuffer();

    uint32_t position();
    void position(uint32_t position);
    uint32_t limit();
    void limit(uint32_t limit);
    uint32_t capacity();
    uint32_t remaining();
    bool hasRemaining();
    void rewind();
    void compact();
    void flip();
    void clear();
    void skip(uint32_t length);
    void clearCapacity();
    uint8_t *bytes();

    // Write methods (stubs)
    void writeInt32(int32_t x, bool *error) {}
    void writeInt64(int64_t x, bool *error) {}
    void writeBool(bool value, bool *error) {}
    void writeBytes(uint8_t *b, uint32_t length, bool *error) {}
    void writeBytes(uint8_t *b, uint32_t offset, uint32_t length, bool *error) {}
    void writeBytes(void *b, bool *error) {}
    void writeBytes(NativeByteBuffer *b, bool *error) {}
    void writeByte(uint8_t i, bool *error) {}
    void writeString(std::string s, bool *error) {}
    void writeByteArray(uint8_t *b, uint32_t offset, uint32_t length, bool *error) {}
    void writeByteArray(uint8_t *b, uint32_t length, bool *error) {}
    void writeByteArray(NativeByteBuffer *b, bool *error) {}
    void writeByteArray(void *b, bool *error) {}
    void writeDouble(double d, bool *error) {}
    
    void writeInt32(int32_t x) {}
    void writeInt64(int64_t x) {}
    void writeBool(bool value) {}
    void writeBytes(uint8_t *b, uint32_t length) {}
    void writeBytes(uint8_t *b, uint32_t offset, uint32_t length) {}
    void writeBytes(void *b) {}
    void writeBytes(NativeByteBuffer *b) {}
    void writeByte(uint8_t i) {}
    void writeString(std::string s) {}
    void writeByteArray(uint8_t *b, uint32_t offset, uint32_t length) {}
    void writeByteArray(uint8_t *b, uint32_t length) {}
    void writeByteArray(NativeByteBuffer *b) {}
    void writeByteArray(void *b) {}
    void writeDouble(double d) {}

    // Read methods (stubs)
    uint32_t readUint32(bool *error) { return 0; }
    uint64_t readUint64(bool *error) { return 0; }
    int32_t readInt32(bool *error) { return 0; }
    int32_t readBigInt32(bool *error) { return 0; }
    int64_t readInt64(bool *error) { return 0; }
    uint8_t readByte(bool *error) { return 0; }
    bool readBool(bool *error) { return false; }
    void readBytes(uint8_t *b, uint32_t length, bool *error) {}
    void *readBytes(uint32_t length, bool *error) { return nullptr; }
    std::string readString(bool *error) { return ""; }
    void *readByteArray(bool *error) { return nullptr; }
    NativeByteBuffer *readByteBuffer(bool copy, bool *error) { return nullptr; }
    double readDouble(bool *error) { return 0.0; }

    void reuse();
    
    #ifdef ANDROID
    jobject getJavaByteBuffer();
    #endif

private:
    void writeBytesInternal(uint8_t *b, uint32_t offset, uint32_t length);

    uint8_t *buffer = nullptr;
    bool calculateSizeOnly = false;
    bool sliced = false;
    uint32_t _position = 0;
    uint32_t _limit = 0;
    uint32_t _capacity = 0;
    bool bufferOwner = true;
    #ifdef ANDROID
    jobject javaByteBuffer = nullptr;
    #endif
};

// --- BuffersStorage stub (must match tgnet/BuffersStorage.h) ---
class BuffersStorage {
public:
    BuffersStorage(bool threadSafe = true);
    NativeByteBuffer *getFreeBuffer(uint32_t size);
    void reuseFreeBuffer(NativeByteBuffer *buffer);
    static BuffersStorage &getInstance();

private:
    std::vector<NativeByteBuffer *> freeBuffers8;
    std::vector<NativeByteBuffer *> freeBuffers128;
    std::vector<NativeByteBuffer *> freeBuffers1024;
    std::vector<NativeByteBuffer *> freeBuffers4096;
    std::vector<NativeByteBuffer *> freeBuffers16384;
    std::vector<NativeByteBuffer *> freeBuffers32768;
    std::vector<NativeByteBuffer *> freeBuffersBig;
    bool isThreadSafe = true;
    pthread_mutex_t mutex;
};

// --- Implementations ---

NativeByteBuffer::NativeByteBuffer(uint32_t size) 
    : buffer(new uint8_t[size]()), _capacity(size), _limit(size), bufferOwner(true) {}

NativeByteBuffer::NativeByteBuffer(bool calculate) 
    : buffer(nullptr), calculateSizeOnly(calculate), bufferOwner(true) {}

NativeByteBuffer::NativeByteBuffer(uint8_t *buff, uint32_t length) 
    : buffer(buff), _capacity(length), _limit(length), bufferOwner(false) {}

NativeByteBuffer::~NativeByteBuffer() {
    if (bufferOwner && buffer) {
        delete[] buffer;
    }
}

uint32_t NativeByteBuffer::position() { return _position; }
void NativeByteBuffer::position(uint32_t pos) { _position = pos; }
uint32_t NativeByteBuffer::limit() { return _limit; }
void NativeByteBuffer::limit(uint32_t lim) { _limit = lim; }
uint32_t NativeByteBuffer::capacity() { return _capacity; }
uint32_t NativeByteBuffer::remaining() { return _limit > _position ? _limit - _position : 0; }
bool NativeByteBuffer::hasRemaining() { return _position < _limit; }
void NativeByteBuffer::rewind() { _position = 0; }
void NativeByteBuffer::compact() { 
    if (_position > 0) {
        memmove(buffer, buffer + _position, _limit - _position);
        _limit = _limit - _position;
        _position = 0;
    }
}
void NativeByteBuffer::flip() { _limit = _position; _position = 0; }
void NativeByteBuffer::clear() { _position = 0; _limit = _capacity; }
void NativeByteBuffer::skip(uint32_t length) { _position += length; }
void NativeByteBuffer::clearCapacity() { 
    if (bufferOwner && buffer) { delete[] buffer; }
    buffer = nullptr; _capacity = 0; _limit = 0; _position = 0;
}
uint8_t* NativeByteBuffer::bytes() { return buffer; }

void NativeByteBuffer::writeBytesInternal(uint8_t *b, uint32_t offset, uint32_t length) {
    if (buffer && _position + length <= _capacity) {
        memcpy(buffer + _position + offset, b, length);
        _position += length;
        if (_position > _limit) _limit = _position;
    }
}

void NativeByteBuffer::reuse() { 
    _position = 0; 
    _limit = _capacity; 
}

#ifdef ANDROID
jobject NativeByteBuffer::getJavaByteBuffer() { return javaByteBuffer; }
#endif

BuffersStorage::BuffersStorage(bool threadSafe) : isThreadSafe(threadSafe) {
    pthread_mutex_init(&mutex, nullptr);
}

NativeByteBuffer* BuffersStorage::getFreeBuffer(uint32_t size) {
    std::vector<NativeByteBuffer*> *pool = &freeBuffersBig;
    if (size <= 8) pool = &freeBuffers8;
    else if (size <= 128) pool = &freeBuffers128;
    else if (size <= 1024) pool = &freeBuffers1024;
    else if (size <= 4096) pool = &freeBuffers4096;
    else if (size <= 16384) pool = &freeBuffers16384;
    else if (size <= 32768) pool = &freeBuffers32768;
    
    NativeByteBuffer* buffer = nullptr;
    if (isThreadSafe) pthread_mutex_lock(&mutex);
    if (!pool->empty()) {
        buffer = pool->back();
        pool->pop_back();
    }
    if (isThreadSafe) pthread_mutex_unlock(&mutex);
    
    if (!buffer) {
        buffer = new NativeByteBuffer(size);
    } else {
        buffer->clearCapacity();
        buffer = new NativeByteBuffer(size); // Simplified: always create new
    }
    return buffer;
}

void BuffersStorage::reuseFreeBuffer(NativeByteBuffer *buffer) {
    if (!buffer) return;
    uint32_t cap = buffer->capacity();
    std::vector<NativeByteBuffer*> *pool = &freeBuffersBig;
    if (cap <= 8) pool = &freeBuffers8;
    else if (cap <= 128) pool = &freeBuffers128;
    else if (cap <= 1024) pool = &freeBuffers1024;
    else if (cap <= 4096) pool = &freeBuffers4096;
    else if (cap <= 16384) pool = &freeBuffers16384;
    else if (cap <= 32768) pool = &freeBuffers32768;
    
    if (isThreadSafe) pthread_mutex_lock(&mutex);
    pool->push_back(buffer);
    if (isThreadSafe) pthread_mutex_unlock(&mutex);
}

BuffersStorage& BuffersStorage::getInstance() {
    static BuffersStorage instance(true);
    return instance;
}