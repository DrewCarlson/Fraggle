package org.drewcarlson.fraggle.models

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Custom serializer for [ExecutorType] that maps legacy sandbox type names
 * ("permissive", "docker", "gvisor") to [ExecutorType.LOCAL] for backward compatibility.
 */
object ExecutorTypeSerializer : KSerializer<ExecutorType> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ExecutorType", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): ExecutorType {
        val value = decoder.decodeString().lowercase()
        return when (value) {
            "local", "permissive", "docker", "gvisor" -> ExecutorType.LOCAL
            "remote" -> ExecutorType.REMOTE
            else -> ExecutorType.LOCAL
        }
    }

    override fun serialize(encoder: Encoder, value: ExecutorType) {
        encoder.encodeString(value.name.lowercase())
    }
}
