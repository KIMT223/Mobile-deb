# Mobile Debian (com.mobiledeb)

1. 用 Android Studio 打开本目录（会自动生成 gradle wrapper 并同步）。
2. 按 app/src/main/jniLibs/arm64-v8a/README.txt 放入 proot 文件。
3. 手机上把 Debian arm64 rootfs 压缩包 (.tar.gz/.tgz/.tar.xz) 放到
   /storage/emulated/0/mobile_deb/
4. 安装运行，授予「所有文件访问」权限，首次启动自动解压到应用内部存储。

Debian 内 /mnt/shared 即共享目录 /storage/emulated/0/mobile_deb/。

## 终端界面 (WebView + xterm.js)

xterm.js 不放进仓库，由 GitHub Actions 构建时自动下载到 `app/src/main/assets/xterm/`。
本地用 Android Studio 构建时，先手动执行一次：

```bash
mkdir -p /tmp/xt app/src/main/assets/xterm
cd /tmp/xt && npm init -y && npm install @xterm/xterm@5.5.0 @xterm/addon-fit@0.10.0 && cd -
cp /tmp/xt/node_modules/@xterm/xterm/lib/xterm.js        app/src/main/assets/xterm/
cp /tmp/xt/node_modules/@xterm/xterm/css/xterm.css       app/src/main/assets/xterm/
cp /tmp/xt/node_modules/@xterm/addon-fit/lib/addon-fit.js app/src/main/assets/xterm/
```
