# DualWall R8 规则
# Compose 与 material3 自带的 consumer ProGuard 规则已覆盖绝大部分，
# 这里只保留 Kotlin 协程与反射场景下偶发的 missing-class 警告。

# Kotlin 协程的调试/并发检测类在 release 下不存在，属正常
-dontwarn kotlinx.coroutines.**

# Kotlin 标准库内省/反射相关的提示
-dontwarn kotlin.**

# 协程的 ServiceLoader 入口要保留，否则主线程调度器在 R8 下可能加载失败
-keep class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keep class kotlinx.coroutines.CoroutineExceptionHandler { *; }
