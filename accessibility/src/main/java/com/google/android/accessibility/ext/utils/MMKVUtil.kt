package com.google.android.accessibility.ext.utils
import android.os.Parcelable
import android.text.TextUtils
import com.tencent.mmkv.MMKV

//import timber.log.Timber

class MMKVUtil {
    companion object {

        init {
            //第一次调用 put 或 get 等静态方法时,该代码块会执行
            val rootDir = MMKV.initialize(LibCtxProvider.appContext)
//            Timber.d("存储路径 rootDir = $rootDir")
        }

        fun put(key: String, value: Any?): Boolean {
            if (key.isEmpty()) return false

            val mmkv = MMKV.defaultMMKV()

            if (value == null) {
                mmkv.removeValueForKey(key)
                return false
            }

            return when (value) {
                is Int -> mmkv.encode(key, value)
                is String -> mmkv.encode(key, value)
                is Double -> mmkv.encode(key, value)
                is Float -> mmkv.encode(key, value)
                is Boolean -> mmkv.encode(key, value)
                is Long -> mmkv.encode(key, value)
                is ByteArray -> mmkv.encode(key, value)
                is Parcelable -> mmkv.encode(key, value)
                is Set<*> -> {
                    // 安全检查：只允许 String
                    if (value.all { it is String }) {
                        mmkv.encode(key, value as Set<String>)
                    } else return false
                }
                else -> false
            }
        }


        @Suppress("UNCHECKED_CAST")
        fun <T> get(key: String, defaultValue: T): T {
            val mmkv = MMKV.defaultMMKV()
            if (!mmkv.containsKey(key)) return defaultValue

            return when (defaultValue) {
                is Int -> mmkv.decodeInt(key, defaultValue) as T
                is String -> mmkv.decodeString(key, defaultValue) as T
                is Double -> mmkv.decodeDouble(key, defaultValue) as T
                is Float -> mmkv.decodeFloat(key, defaultValue) as T
                is Boolean -> mmkv.decodeBool(key, defaultValue) as T
                is Long -> mmkv.decodeLong(key, defaultValue) as T
                is ByteArray -> mmkv.decodeBytes(key, defaultValue) as T
                is Parcelable -> mmkv.decodeParcelable(key, defaultValue::class.java) as T
                is Set<*> -> {
                    val defaultSet = defaultValue as? Set<String> ?: emptySet()
                    mmkv.decodeStringSet(key, defaultSet) as T
                }
                else -> defaultValue
            }
        }


        fun <T : Parcelable> get(
            key: String,
            tClass: Class<T>
        ): T? {
            return MMKV.defaultMMKV().decodeParcelable(key, tClass)
        }

        fun <T : Parcelable> get(
            key: String,
            tClass: Class<T>,
            defaultValue: T
        ): T {
            return MMKV.defaultMMKV().decodeParcelable(key, tClass, defaultValue)!!
        }
    }
}
