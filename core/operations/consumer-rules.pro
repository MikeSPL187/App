
# commons-compress: необязательные кодеки, которые мы не подключаем (zstd, brotli и т. п.).
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.objectweb.asm.**
# junrar тянет slf4j-api без реализации; логирование нам не нужно.
-dontwarn org.slf4j.impl.**
