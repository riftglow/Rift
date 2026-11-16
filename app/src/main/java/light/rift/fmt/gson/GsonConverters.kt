package light.rift.fmt.gson

import androidx.room.TypeConverter
import light.rift.core.utils.JavaUtil

object GsonConverters {

    @TypeConverter
    @JvmStatic
    fun toJson(value: Any?): String {
        if (value is Collection<*>) {
            if (value.isEmpty()) return ""
        }
        return JavaUtil.gson.toJson(value)
    }

    @TypeConverter
    @JvmStatic
    fun toList(value: String?): List<*> {
        if (JavaUtil.isNullOrBlank(value)) return emptyList<Any?>()
        return JavaUtil.gson.fromJson(value, List::class.java)
    }

    @TypeConverter
    @JvmStatic
    fun toSet(value: String?): Set<*> {
        if (JavaUtil.isNullOrBlank(value)) return emptySet<Any?>()
        return JavaUtil.gson.fromJson(value, Set::class.java)
    }
}
