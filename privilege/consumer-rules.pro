# 特权模块的 R8 规则。打包时跟着 app 一起生效（见 build.gradle.kts 的 consumerProguardFiles）。
#
# R8 只认代码里写出来的调用，下面这些都是按名字找到的，它看不出有人在用，不留的话会被当成死代码删掉。
# 编译时不会报错，要等装上之后配对失败、特权进程起不来才发现。

# 特权进程的入口：Starter 拼出 app_process 命令，按类名字符串（PrivilegeContract.SERVER_MAIN_CLASS）拉起它，
# App 里没有任何地方直接调用。留住 main，R8 会顺着它把特权进程用到的代码都留下。
-keep class me.wjz.kitten.privilege.server.ServerMain {
    public static void main(java.lang.String[]);
}

# libadb：配对、连 adbd。没自带规则，整个库也不大，整包留着最稳。
# 它直接调用 BouncyCastle 里的 AES、GCM、HKDF，那些 R8 顺着调用找得到，BouncyCastle 不用整包留。
-keep class io.github.muntashirakon.adb.** { *; }

# spake2：配对码握手，走 JNI。原生代码按完整的类名、方法名绑定 Java 方法，也可能反过来按名字找 Java 的字段。
-keep class io.github.muntashirakon.crypto.spake2.** { *; }

# sun-security：生成 ADB 用的自签证书（见 KittenAdbManager）。它按 OID 用类名反射创建证书扩展（OIDMap），
# 也按名字加载密钥类（PKCS8Key、X509Key）。
-keep class android.sun.security.** { *; }

# conscrypt 自带了整包保留的规则，这里不用再写。
