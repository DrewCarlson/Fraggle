package fraggle.provider

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Custom serializer for [NativeChatInput] which can be either:
 * - A plain string: `"Hello"` → [NativeChatInput.Text]
 * - An array of input items: `[{type: "message", content: "Hello"}]` → [NativeChatInput.Messages]
 */
internal object NativeChatInputSerializer : KSerializer<NativeChatInput> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("NativeChatInput")

    override fun serialize(encoder: Encoder, value: NativeChatInput) {
        val jsonEncoder = encoder as JsonEncoder
        when (value) {
            is NativeChatInput.Text -> jsonEncoder.encodeJsonElement(JsonPrimitive(value.text))
            is NativeChatInput.Messages -> jsonEncoder.encodeSerializableValue(
                ListSerializer(NativeChatInputItem.serializer()),
                value.items,
            )
        }
    }

    override fun deserialize(decoder: Decoder): NativeChatInput {
        val jsonDecoder = decoder as JsonDecoder
        val element = jsonDecoder.decodeJsonElement()
        return when {
            element is JsonPrimitive -> NativeChatInput.Text(element.jsonPrimitive.content)
            else -> NativeChatInput.Messages(
                element.jsonArray.map {
                    jsonDecoder.json.decodeFromJsonElement(NativeChatInputItem.serializer(), it)
                }
            )
        }
    }
}
