import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 把 classes.dex 塞进 aapt2 产出的资源 APK。
 *
 * <p>为什么不用 jar/zip：Git Bash 默认不带 zip，JDK 的 jar 在更新已有压缩包时
 * 可能写入 META-INF/MANIFEST.MF，而 APK 里出现多余条目并不安全。
 * 这里用 java.util.zip 逐条复制，只追加需要的条目。
 *
 * <p><b>必须保留原条目的压缩方式</b>：aapt2 会把 {@code resources.arsc} 以 STORED
 * 写出，而 targetSdk ≥ 30 的 APK 若 resources.arsc 被压缩，安装时会被
 * INSTALL_PARSE_FAILED_RESOURCES_ARSC_COMPRESSED 直接拒绝。
 *
 * <p>用法：java ZipAdd.java in.apk out.apk classes.dex
 */
public final class ZipAdd {

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println("用法: java ZipAdd.java <in.apk> <out.apk> <要追加的文件>...");
            System.exit(2);
        }

        File in = new File(args[0]);
        File out = new File(args[1]);
        if (out.exists() && !out.delete()) {
            throw new IOException("无法覆盖输出文件: " + out);
        }

        byte[] buf = new byte[1 << 16];
        int stored = 0;

        // 用 ZipFile（随机读取）而非 ZipInputStream：STORED 条目要求在写入前
        // 提供 size 与 crc，这两个值只有中央目录里才有；ZipInputStream 要读完
        // 条目之后才填充它们，无法边读边写。
        try (ZipFile zin = new ZipFile(in);
             ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(out))) {

            Enumeration<? extends ZipEntry> entries = zin.entries();
            while (entries.hasMoreElements()) {
                ZipEntry src = entries.nextElement();
                String name = src.getName();

                // 输入里不应有签名残留（aapt2 不会产生，这里只是防御）
                if (name.startsWith("META-INF/") && !name.startsWith("META-INF/services/")) {
                    continue;
                }

                ZipEntry copy = new ZipEntry(name);
                copy.setTime(src.getTime());
                if (src.getMethod() == ZipEntry.STORED) {
                    copy.setMethod(ZipEntry.STORED);
                    copy.setSize(src.getSize());
                    copy.setCrc(src.getCrc());
                    stored++;
                } else {
                    copy.setMethod(ZipEntry.DEFLATED);
                }

                zout.putNextEntry(copy);
                try (InputStream is = zin.getInputStream(src)) {
                    int n;
                    while ((n = is.read(buf)) > 0) {
                        zout.write(buf, 0, n);
                    }
                }
                zout.closeEntry();
            }

            for (int i = 2; i < args.length; i++) {
                File extra = new File(args[i]);
                if (!extra.isFile()) {
                    throw new IOException("待追加文件不存在: " + extra);
                }
                ZipEntry add = new ZipEntry(extra.getName());
                add.setMethod(ZipEntry.DEFLATED);
                add.setTime(extra.lastModified());
                zout.putNextEntry(add);
                try (InputStream is = new java.io.FileInputStream(extra)) {
                    int n;
                    while ((n = is.read(buf)) > 0) {
                        zout.write(buf, 0, n);
                    }
                }
                zout.closeEntry();
                System.out.println("  追加 " + extra.getName() + " (" + extra.length() + " 字节)");
            }
        }

        System.out.println("  已生成 " + out.getName() + " (" + out.length()
                + " 字节, 保留未压缩条目 " + stored + " 个)");
    }

    private ZipAdd() {
    }
}
