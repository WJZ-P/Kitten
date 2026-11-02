# app 的 R8 规则。特权模块自己的规则在 privilege/consumer-rules.pro。

# 只删没用到的代码，不改名：
# - 按名字找类的地方（特权进程入口、跨进程传的 Parcelable、加密库里的反射）改了名就对不上，不改名少踩一批坑；
# - 项目是开源的，混淆没有意义；用户报 bug 贴上来的堆栈也直接看得懂。
-dontobfuscate

# 堆栈里留着文件名和行号，崩溃日志才定位得到哪一行。
-keepattributes SourceFile,LineNumberTable
