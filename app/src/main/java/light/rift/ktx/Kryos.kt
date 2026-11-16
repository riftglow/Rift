package light.rift.ktx

import android.os.Parcel
import android.os.Parcelable
import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import java.io.InputStream
import java.io.OutputStream

/** Wrap a stream in a Kryo byte-buffer reader. */
fun InputStream.byteBuffer() = ByteBufferInput(this)

/** Wrap a stream in a Kryo byte-buffer writer. */
fun OutputStream.byteBuffer() = ByteBufferOutput(this)

/** Read a length-prefixed list of strings. */
fun ByteBufferInput.readStringList(): List<String> {
    val count = readInt()
    val out = ArrayList<String>(count)
    repeat(count) { out += readString() }
    return out
}

/** Read a length-prefixed set of strings, preserving insertion order. */
fun ByteBufferInput.readStringSet(): Set<String> {
    val count = readInt()
    val out = LinkedHashSet<String>()
    repeat(count) { out += readString() }
    return out
}

/** Write a length-prefixed list of strings. */
fun ByteBufferOutput.writeStringList(list: List<String>) {
    writeInt(list.size)
    list.forEach { writeString(it) }
}

/** Write a length-prefixed set of strings. */
fun ByteBufferOutput.writeStringList(list: Set<String>) {
    writeInt(list.size)
    list.forEach { writeString(it) }
}

/** Serialise this parcelable into a byte array. */
fun Parcelable.marshall(): ByteArray {
    val parcel = Parcel.obtain()
    try {
        writeToParcel(parcel, 0)
        return parcel.marshall()
    } finally {
        parcel.recycle()
    }
}

/**
 * Rebuild a [Parcel] from bytes produced by [marshall].
 * The parcel is rewound to position 0 before it is returned.
 */
fun ByteArray.unmarshall(): Parcel {
    val parcel = Parcel.obtain()
    parcel.unmarshall(this, 0, size)
    parcel.setDataPosition(0)
    return parcel
}

/** Rebuild an object from bytes produced by [marshall] using [constructor]. */
fun <T> ByteArray.unmarshall(constructor: (Parcel) -> T): T {
    val parcel = unmarshall()
    try {
        return constructor(parcel)
    } finally {
        parcel.recycle()
    }
}
