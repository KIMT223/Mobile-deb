把 aarch64 的 proot 文件放在这里并按下面名字命名（Termux 的 proot 包里取）：

  libproot.so          <- proot 可执行文件
  libproot-loader.so   <- libexec/proot/loader
  libtalloc.so         <- 若你的 proot 动态链接 talloc（Termux 版需要）

用静态编译的 proot 则只需前两个。
