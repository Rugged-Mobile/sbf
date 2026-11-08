package ruggedmobile.sbf

import java.io.InputStream

/** Adapts a JVM [InputStream] (file, socket, Android serial/USB stream) to a [ByteSource]. */
fun InputStream.asByteSource(): ByteSource = ByteSource { buffer, offset, length -> read(buffer, offset, length) }
