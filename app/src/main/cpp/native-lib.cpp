#include <jni.h>
#include <string>

extern "C" JNIEXPORT jstring JNICALL
Java_com_adina_flam_MainActivity_stringFromJNI(
        JNIEnv* env,
        jobject /* this */) {
    return env->NewStringUTF("Hello from C++");
}