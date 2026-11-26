# 原生库构建说明（LibRaw）

`app/src/main/jniLibs/{arm64-v8a,x86_64}/libraw.so` 是预编译产物，来源是 **LibRaw 0.22.3**（LGPL-2.1）。
本文说明如何自行复现这两个文件，方便审计与二次分发。

---

## 一、准备

| 依赖 | 版本 | 说明 |
|---|---|---|
| LibRaw | 0.22.3 | <https://www.libraw.org/> 下载源码包 |
| Android NDK | 29.0.14206865 | 提供 `aarch64-linux-android26-clang++` / `x86_64-linux-android26-clang++` |
| CMake | 可选 | 也可直接用 clang 命令行 |

---

## 二、编译 LibRaw 静态库

以 arm64 为例（x86_64 把三元组换成 `x86_64-linux-android26`）：

```bash
NDK=/path/to/android-ndk-r29
CXX=$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android26-clang++

cd LibRaw-0.22.3
mkdir -p out/arm64 && cd out/arm64

# 一次性生成编译参数文件（避免超长命令行）
$CXX -c ../../src/libraw_cxx.cpp -o libraw_cxx.o \
    -O2 -std=c++17 -fPIC -DNDEBUG -DLIBRAW_NOTHREADS \
    -I../../ -I../../internal
# 其余源文件同理（见源码目录 src/*.cpp）

# 归档
$NDK/.../llvm-ar rcs libraw.a *.o
```

> 需要排除 `*_ph.cpp`（这些文件由 `libraw_cxx.cpp` 直接包含，单独编译会重复定义）。
> 需要包含 `src/write/*.cpp` 之外的全部解码源文件。

---

## 三、链接 JNI 桥接

```bash
$CXX -shared -o libraw.so ../libraw_jni.cpp libraw.a \
    -O2 -std=c++17 -fPIC -static-libstdc++ \
    -Wl,-z,max-page-size=16384 \
    -Wl,--no-undefined
```

关键参数说明：

| 参数 | 原因 |
|---|---|
| `-Wl,-z,max-page-size=16384` | 满足 **16 KB 页**要求（Android 15+ 机型） |
| `-static-libstdc++` | 避免依赖系统 `libc++_shared.so`，减少体积与兼容问题 |
| `-DLIBRAW_NOTHREADS` | 单线程解码，避免 Android 上线程池初始化开销 |

---

## 四、放置产物

```
app/src/main/jniLibs/arm64-v8a/libraw.so
app/src/main/jniLibs/x86_64/libraw.so     # 仅调试模拟器需要
```

之后 `./gradlew assembleDebug` 即可。

---

## 五、LGPL 合规

本仓库：
- 未修改 LibRaw 源码
- 以**动态库**形式链接（`System.loadLibrary("raw")`）
- 提供了 JNI 桥接源码（`app/src/main/cpp/libraw_jni.cpp`）

因此使用者可以：
1. 用自行编译的 `libraw.so`（哪怕是被修改过的版本）替换仓库中的产物而不必修改本仓库任何代码；
2. 从 LibRaw 官方获取对应版本源码。

如果你修改了 LibRaw 源码并分发，请同样公开其源码（LGPL-2.1 第 6 条）。
