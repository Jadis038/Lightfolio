#include <jni.h>
#include <android/log.h>
#include <cstdio>
#include <cstdlib>

#include "libraw/libraw.h"

#define LOG_TAG "YingCeRaw"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

struct Handle {
    LibRaw *raw = nullptr;
    bool unpacked = false;
    bool processed = false;
};

inline Handle *H(jlong h) { return reinterpret_cast<Handle *>(h); }

/**
 * 画质优先的处理参数：
 * - 16bit 输出、AHD 去马赛克、相机白平衡
 * - 不做自动亮度拉伸（no_auto_bright）、不降饱和
 * - highlight=3：高光不裁切（保留信息，避免死白）
 * - half_size=0：禁止半尺寸（那等于降采样）
 */
void applyParams(LibRaw *r, int bps, int halfSize) {
    auto &p = r->imgdata.params;
    p.output_bps = bps;
    p.output_tiff = 0;
    p.output_color = 1;   // sRGB
    p.user_qual = 3;      // AHD
    p.user_black = -1;
    p.no_auto_bright = 0;   // 线性 DNG（如 iPhone ProRAW）必须允许自动亮度，否则整幅偏暗
    p.use_camera_wb = 1;
    p.use_auto_wb = 0;
    p.highlight = 2;
    p.half_size = halfSize;   // 详情面板展开时允许半尺寸（内容被遮住，省一半以上时间）
    p.four_color_rgb = 1;
    p.bright = 1.0f;
    p.aber[0] = p.aber[1] = p.aber[2] = p.aber[3] = 1.0f;
    p.user_sat = 0;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_yingce_gallery_raw_RawNative_open(JNIEnv *env, jclass, jstring jpath) {
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    auto *h = new Handle();
    h->raw = new LibRaw();
    int rc = h->raw->open_file(path);
    env->ReleaseStringUTFChars(jpath, path);
    if (rc != LIBRAW_SUCCESS) {
        LOGE("open_file failed rc=%d", rc);
        delete h->raw;
        delete h;
        return 0;
    }
    return reinterpret_cast<jlong>(h);
}

/** [rawWidth, rawHeight, width, height, colors, thumbFormat, thumbBytes, flip, black, maximum] */
JNIEXPORT jintArray JNICALL
Java_com_yingce_gallery_raw_RawNative_info(JNIEnv *env, jclass, jlong handle) {
    Handle *h = H(handle);
    if (!h || !h->raw) return nullptr;
    LibRaw *r = h->raw;
    // 12 个值：原 10 个 + iwidth/iheight（图像缓冲的真实跨距，用于正确索引）
    jint v[12];
    v[0] = r->imgdata.sizes.raw_width;
    v[1] = r->imgdata.sizes.raw_height;
    v[2] = r->imgdata.sizes.width;
    v[3] = r->imgdata.sizes.height;
    v[4] = r->imgdata.idata.colors;
    v[5] = r->imgdata.thumbnail.tformat;
    v[6] = (jint) r->imgdata.thumbnail.tlength;
    v[7] = r->imgdata.sizes.flip;
    v[8] = (jint) r->imgdata.color.black;
    v[9] = (jint) r->imgdata.color.maximum;
    v[10] = r->imgdata.sizes.iwidth;
    v[11] = r->imgdata.sizes.iheight;
    jintArray out = env->NewIntArray(12);
    if (!out) return nullptr;
    env->SetIntArrayRegion(out, 0, 12, v);
    return out;
}

/** "make|model|normalized_model|software" */
JNIEXPORT jstring JNICALL
Java_com_yingce_gallery_raw_RawNative_cameraInfo(JNIEnv *env, jclass, jlong handle) {
    Handle *h = H(handle);
    if (!h || !h->raw) return nullptr;
    auto &i = h->raw->imgdata.idata;
    char buf[512];
    snprintf(buf, sizeof(buf), "%s|%s|%s|%s", i.make, i.model, i.normalized_model, i.software);
    return env->NewStringUTF(buf);
}

/** 内嵌预览（通常 JPEG）。 */
JNIEXPORT jbyteArray JNICALL
Java_com_yingce_gallery_raw_RawNative_thumb(JNIEnv *env, jclass, jlong handle) {
    Handle *h = H(handle);
    if (!h || !h->raw) return nullptr;
    if (h->raw->unpack_thumb() != LIBRAW_SUCCESS) return nullptr;
    auto &t = h->raw->imgdata.thumbnail;
    if (t.tlength <= 0 || t.thumb == nullptr) return nullptr;
    jbyteArray out = env->NewByteArray(t.tlength);
    if (!out) return nullptr;
    env->SetByteArrayRegion(out, 0, t.tlength, reinterpret_cast<const jbyte *>(t.thumb));
    return out;
}

/**
 * 解码一个区域，返回 ARGB_8888 的 int[]（长度为实际裁剪后的 w*h）。
 * 全分辨率处理，不做任何缩放；坐标越界会被夹紧，失败一律返回 null（绝不越界读）。
 */
JNIEXPORT jintArray JNICALL
Java_com_yingce_gallery_raw_RawNative_decodeRegion(
        JNIEnv *env, jclass, jlong handle, jint x, jint y, jint w, jint hgt, jint bps, jint half) {
    Handle *h = H(handle);
    if (!h || !h->raw) return nullptr;
    LibRaw *r = h->raw;

    if (!h->unpacked) {
        int rc = r->unpack();
        if (rc != LIBRAW_SUCCESS) {
            LOGE("unpack failed rc=%d", rc);
            return nullptr;
        }
        h->unpacked = true;
    }
    if (!h->processed) {
        applyParams(r, bps, half);
        int rc = r->dcraw_process();
        if (rc != LIBRAW_SUCCESS) {
            LOGE("dcraw_process failed rc=%d", rc);
            return nullptr;
        }
        h->processed = true;
    }

    ushort (*img)[4] = r->imgdata.image;
    if (img == nullptr) return nullptr;

    const int outW = r->imgdata.sizes.width;
    const int outH = r->imgdata.sizes.height;
    // image[] 的行跨距是 iwidth（未裁切宽度），不是 width；用 width 索引会错位
    const int stride = r->imgdata.sizes.iwidth > 0 ? r->imgdata.sizes.iwidth : outW;
    if (outW <= 0 || outH <= 0) return nullptr;

    int cx = x < 0 ? 0 : (x > outW ? outW : x);
    int cy = y < 0 ? 0 : (y > outH ? outH : y);
    int cw = w;
    if (cx + cw > outW) cw = outW - cx;
    int ch = hgt;
    if (cy + ch > outH) ch = outH - cy;
    if (cw <= 0 || ch <= 0) return nullptr;

    // 先探测缓冲到底是 8bit 还是 16bit：部分 DNG/CR3 即使请求 output_bps=8，
    // LibRaw 仍会给出 16bit 值；这里按实际最大值自适应右移，避免整幅被截成白色。
    int sampleMax = 0;
    {
        const int stepY = (ch > 64) ? (ch / 64) : 1;
        const int stepX = (cw > 64) ? (cw / 64) : 1;
        for (int row = 0; row < ch; row += stepY) {
            const size_t srcRow = (size_t) (cy + row) * (size_t) stride;
            for (int col = 0; col < cw; col += stepX) {
                const ushort *px = img[srcRow + (size_t) (cx + col)];
                if (px[0] > sampleMax) sampleMax = px[0];
                if (px[1] > sampleMax) sampleMax = px[1];
                if (px[2] > sampleMax) sampleMax = px[2];
            }
        }
    }
    const bool sixteenBit = sampleMax > 255;
    const int adaptiveShift = sixteenBit ? 8 : 0;

    jintArray out = env->NewIntArray(cw * ch);
    if (!out) return nullptr;
    jint *buf = (jint *) env->GetPrimitiveArrayCritical(out, nullptr);
    if (!buf) {
        env->DeleteLocalRef(out);
        return nullptr;
    }
    const int shift = adaptiveShift;
    for (int row = 0; row < ch; row++) {
        const size_t srcRow = (size_t) (cy + row) * (size_t) stride;
        for (int col = 0; col < cw; col++) {
            const ushort *px = img[srcRow + (size_t) (cx + col)];
            int rr = (px[0] >> shift) & 0xFF;
            int gg = (px[1] >> shift) & 0xFF;
            int bb = (px[2] >> shift) & 0xFF;
            if (rr > 255) rr = 255;
            if (gg > 255) gg = 255;
            if (bb > 255) bb = 255;
            buf[(size_t) row * cw + col] = (jint) ((0xFFu << 24) | (rr << 16) | (gg << 8) | bb);
        }
    }
    env->ReleasePrimitiveArrayCritical(out, buf, 0);
    return out;
}

JNIEXPORT void JNICALL
Java_com_yingce_gallery_raw_RawNative_close(JNIEnv *, jclass, jlong handle) {
    Handle *h = H(handle);
    if (!h) return;
    if (h->raw) {
        h->raw->recycle();
        delete h->raw;
    }
    delete h;
}

}  // extern "C"
