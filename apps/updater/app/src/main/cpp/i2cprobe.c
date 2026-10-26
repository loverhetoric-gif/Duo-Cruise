#include <jni.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <string.h>
#include <stdio.h>
#include <time.h>
#include <sys/ioctl.h>
#include <linux/i2c.h>
#include <linux/i2c-dev.h>

/* /dev/i2c-N を開き、MFi 認証チップの候補 0x10/0x11 にデバイス版(レジスタ 0x00)を問い合わせる。 */
JNIEXPORT jstring JNICALL
Java_com_yuki_duocruise_updater_AuthCheck_probe(JNIEnv *env, jclass cls, jstring jpath) {
    const char *path = (*env)->GetStringUTFChars(env, jpath, 0);
    char out[512];
    int fd = open(path, O_RDWR);
    if (fd < 0) {
        snprintf(out, sizeof out, "開けない(%s)", strerror(errno));
        (*env)->ReleaseStringUTFChars(env, jpath, path);
        return (*env)->NewStringUTF(env, out);
    }
    out[0] = 0;
    int addrs[2] = {0x10, 0x11};
    for (int i = 0; i < 2; i++) {
        int a = addrs[i];
        int found = -1; int lastErr = 0;
        for (int t = 0; t < 40 && found < 0; t++) {
            unsigned char reg = 0x00, val = 0;
            struct i2c_msg w = { .addr = (unsigned short)a, .flags = 0, .len = 1, .buf = &reg };
            struct i2c_rdwr_ioctl_data d1 = { .msgs = &w, .nmsgs = 1 };
            if (ioctl(fd, I2C_RDWR, &d1) < 0) { lastErr = errno; struct timespec s = {0, 20000000}; nanosleep(&s, 0); continue; }
            struct i2c_msg r = { .addr = (unsigned short)a, .flags = I2C_M_RD, .len = 1, .buf = &val };
            struct i2c_rdwr_ioctl_data d2 = { .msgs = &r, .nmsgs = 1 };
            if (ioctl(fd, I2C_RDWR, &d2) < 0) { lastErr = errno; struct timespec s = {0, 20000000}; nanosleep(&s, 0); continue; }
            if (val != 0x00 && val != 0xff) found = val;
        }
        char part[160];
        if (found >= 0) snprintf(part, sizeof part, "0x%02x: 応答あり(版 0x%02x) ", a, found);
        else snprintf(part, sizeof part, "0x%02x: 応答なし(%s) ", a, lastErr ? strerror(lastErr) : "値なし");
        strncat(out, part, sizeof out - strlen(out) - 1);
    }
    close(fd);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    return (*env)->NewStringUTF(env, out);
}
