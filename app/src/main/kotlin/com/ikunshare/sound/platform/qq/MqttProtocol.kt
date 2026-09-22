package com.ikunshare.sound.platform.qq

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer


object MqttProtocol {

    // Packet types
    private const val CONNECT: Byte = 0x10
    private const val CONNACK: Byte = 0x20
    private const val SUBSCRIBE: Byte = (0x82).toByte()
    private const val SUBACK: Byte = (0x90).toByte()
    private const val PUBLISH: Byte = 0x30

    // Property IDs
    private const val AUTH_METHOD: Byte = 0x15
    private const val USER_PROPERTY: Byte = 0x26
    private const val SERVER_REFERENCE: Byte = 0x1C
    private const val REASON_STRING: Byte = 0x1F

    fun buildConnectPacket(
        clientId: String,
        authMethod: String? = null,
        userProperties: List<Pair<String, String>> = emptyList(),
        keepAlive: Int = 45
    ): ByteArray {
        val payload = ByteArrayOutputStream()
        val dos = DataOutputStream(payload)

        dos.writeShort(4)
        dos.write("MQTT".toByteArray())
        dos.writeByte(5) // MQTT 5.0
        dos.writeByte(0x02) // Clean Start
        dos.writeShort(keepAlive)

        val props = buildProperties(authMethod, userProperties)
        writeVariableByteInt(dos, props.size)
        dos.write(props)

        dos.writeShort(clientId.length)
        dos.write(clientId.toByteArray())

        val variableAndPayload = payload.toByteArray()
        val packet = ByteArrayOutputStream()
        packet.write(CONNECT.toInt())
        writeVariableByteInt(DataOutputStream(packet), variableAndPayload.size)
        packet.write(variableAndPayload)
        return packet.toByteArray()
    }

    fun buildSubscribePacket(
        packetId: Int,
        topic: String,
        userProperties: List<Pair<String, String>> = emptyList()
    ): ByteArray {
        val payload = ByteArrayOutputStream()
        val dos = DataOutputStream(payload)

        dos.writeShort(packetId)

        val props = buildProperties(null, userProperties)
        writeVariableByteInt(dos, props.size)
        dos.write(props)

        dos.writeShort(topic.length)
        dos.write(topic.toByteArray())
        dos.writeByte(0) // QoS 0

        val variableAndPayload = payload.toByteArray()
        val packet = ByteArrayOutputStream()
        packet.write(SUBSCRIBE.toInt())
        writeVariableByteInt(DataOutputStream(packet), variableAndPayload.size)
        packet.write(variableAndPayload)
        return packet.toByteArray()
    }

    data class MqttMessage(
        val type: Byte,
        val serverReference: String? = null,
        val userProperties: Map<String, String> = emptyMap(),
        val payload: ByteArray? = null
    )

    fun parsePacket(data: ByteArray): MqttMessage? {
        if (data.isEmpty()) return null
        val buf = ByteBuffer.wrap(data)
        val typeByte = (buf.get().toInt() and 0xF0).toByte()
        decodeVariableByteInt(buf)

        return when (typeByte) {
            CONNACK -> parseConnack(buf)
            SUBACK -> MqttMessage(type = SUBACK)
            PUBLISH -> parsePublish(buf)
            else -> MqttMessage(type = typeByte)
        }
    }

    private fun parseConnack(buf: ByteBuffer): MqttMessage {
        buf.get() // Acknowledge Flags
        val reasonCode = buf.get().toInt() and 0xFF

        if (buf.remaining() <= 0) return MqttMessage(CONNACK)

        val propsLen = decodeVariableByteInt(buf)
        val propsEnd = buf.position() + propsLen
        var serverRef: String? = null

        while (buf.position() < propsEnd && buf.hasRemaining()) {
            when (buf.get()) {
                SERVER_REFERENCE -> serverRef = readUtf8String(buf)
                REASON_STRING -> readUtf8String(buf)
                USER_PROPERTY -> {
                    readUtf8String(buf); readUtf8String(buf)
                }

                else -> break
            }
        }

        if (reasonCode == 0x9D && serverRef != null) {
            return MqttMessage(CONNACK, serverRef)
        }
        return MqttMessage(CONNACK)
    }

    private fun parsePublish(buf: ByteBuffer): MqttMessage {
        readUtf8String(buf) // topic
        val propsLen = decodeVariableByteInt(buf)
        val propsEnd = buf.position() + propsLen
        val userProps = mutableMapOf<String, String>()

        while (buf.position() < propsEnd && buf.hasRemaining()) {
            when (buf.get()) {
                USER_PROPERTY -> {
                    val key = readUtf8String(buf)
                    val value = readUtf8String(buf)
                    userProps[key] = value
                }

                else -> break
            }
        }
        val payload = ByteArray(buf.remaining())
        buf.get(payload)
        return MqttMessage(PUBLISH, null, userProps, payload)
    }

    private fun buildProperties(
        authMethod: String?,
        userProperties: List<Pair<String, String>>
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val dos = DataOutputStream(out)
        if (authMethod != null) {
            dos.writeByte(AUTH_METHOD.toInt())
            dos.writeShort(authMethod.length)
            dos.write(authMethod.toByteArray())
        }
        for ((key, value) in userProperties) {
            dos.writeByte(USER_PROPERTY.toInt())
            dos.writeShort(key.length)
            dos.write(key.toByteArray())
            dos.writeShort(value.length)
            dos.write(value.toByteArray())
        }
        return out.toByteArray()
    }

    private fun writeVariableByteInt(dos: DataOutputStream, value: Int) {
        var v = value
        do {
            var byte = v % 128
            v /= 128
            if (v > 0) byte = byte or 0x80
            dos.writeByte(byte)
        } while (v > 0)
    }

    private fun decodeVariableByteInt(buf: ByteBuffer): Int {
        var value = 0
        var multiplier = 1
        var byte: Int
        do {
            byte = buf.get().toInt() and 0xFF
            value += (byte and 0x7F) * multiplier
            multiplier *= 128
        } while (byte and 0x80 != 0)
        return value
    }

    private fun readUtf8String(buf: ByteBuffer): String {
        val len = buf.short.toInt() and 0xFFFF
        val bytes = ByteArray(len)
        buf.get(bytes)
        return String(bytes)
    }
}
