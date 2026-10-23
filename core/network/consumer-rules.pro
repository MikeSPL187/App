# BouncyCastle регистрирует алгоритмы по именам классов, отсутствующие просто пропускает.
# Оставляем то, что нужно SSH (ключи и подписи EC, RSA, Ed25519, обмен X25519/ECDH/DH, AES и ChaCha20, HMAC и хэши);
# постквантовые и национальные алгоритмы не нужны и занимали бы мегабайты.
-keep class org.bouncycastle.jce.provider.BouncyCastleProvider { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.EC** { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.RSA** { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.EdEC** { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.DH** { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.X509** { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.ec.** { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.rsa.** { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.edec.** { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.dh.** { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.x509.** { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.util.** { *; }
-keep class org.bouncycastle.jcajce.provider.symmetric.AES** { *; }
-keep class org.bouncycastle.jcajce.provider.symmetric.ChaCha** { *; }
-keep class org.bouncycastle.jcajce.provider.symmetric.DESede** { *; }
-keep class org.bouncycastle.jcajce.provider.symmetric.util.** { *; }
-keep class org.bouncycastle.jcajce.provider.digest.** { *; }
-keep class org.bouncycastle.jcajce.provider.drbg.** { *; }
-keep class org.bouncycastle.jcajce.provider.kdf.** { *; }
-keep class org.bouncycastle.jcajce.provider.util.** { *; }
-keep class org.bouncycastle.jcajce.provider.config.** { *; }
-dontwarn org.bouncycastle.**
# sshj: необязательное сжатие и платформенные классы, которых нет на Android.
-dontwarn com.jcraft.jzlib.**
-dontwarn javax.naming.**
-dontwarn sun.security.**
-dontwarn org.ietf.jgss.**
-dontwarn net.i2p.crypto.eddsa.**
# smbj рассылает внутренние события через mbassador: обработчики находятся по аннотации.
-keepclassmembers class * {
    @net.engio.mbassy.listener.Handler *;
}
-keep class net.engio.mbassy.** { *; }
-dontwarn net.engio.mbassy.**
# commons-net создаёт разборщики списков FTP по имени системы сервера.
-keep class org.apache.commons.net.ftp.parser.** { <init>(...); }
-dontwarn org.slf4j.**
