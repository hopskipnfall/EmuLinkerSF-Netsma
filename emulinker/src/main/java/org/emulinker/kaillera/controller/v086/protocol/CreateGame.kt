package org.emulinker.kaillera.controller.v086.protocol

import java.nio.ByteBuffer
import org.emulinker.kaillera.controller.v086.V086Utils
import org.emulinker.kaillera.controller.v086.V086Utils.getNumBytesPlusStopByte
import org.emulinker.util.EmuUtil
import org.emulinker.util.UnsignedUtil.getUnsignedShort
import org.emulinker.util.UnsignedUtil.putUnsignedShort

sealed class CreateGame : V086Message() {
  abstract val romName: String

  data class Notification
  constructor(
    override val messageNumber: Int,
    val username: String,
    override val romName: String,
    val clientType: String,
    val gameId: Int,
    val val1: Int
  ) : CreateGame() {

    override val messageTypeId = ID

    init {
      require(romName.isNotBlank()) { "romName cannot be blank" }
      require(gameId in 0..0xFFFF) { "gameID out of acceptable range: $gameId" }
      require(val1 in 0..0xFFFF) { "val1 out of acceptable range: $val1" }
    }

    override val bodyBytes: Int
      get() =
        username.getNumBytesPlusStopByte() +
          romName.getNumBytesPlusStopByte() +
          clientType.getNumBytesPlusStopByte() +
          V086Utils.Bytes.SHORT +
          V086Utils.Bytes.SHORT

    public override fun writeBodyTo(buffer: ByteBuffer) {
      CreateGameSerializer.write(buffer, this)
    }
  }

  data class Request constructor(override val messageNumber: Int, override val romName: String) :
    CreateGame() {
    override val messageTypeId = ID

    private val username = ""
    private val clientType = ""

    override val bodyBytes: Int
      get() =
        username.getNumBytesPlusStopByte() +
          romName.getNumBytesPlusStopByte() +
          clientType.getNumBytesPlusStopByte() +
          V086Utils.Bytes.SHORT +
          V086Utils.Bytes.SHORT

    public override fun writeBodyTo(buffer: ByteBuffer) {
      CreateGameSerializer.write(buffer, this)
    }
  }

  companion object {
    const val ID: Byte = 0x0A

    const val REQUEST_GAME_ID = 0xFFFF
    const val REQUEST_VAL1 = 0xFFFF
    const val REQUEST_USERNAME = ""
    const val REQUEST_CLIENT_TYPE = ""
  }

  object CreateGameSerializer : MessageSerializer<CreateGame> {
    override val messageTypeId: Byte = ID

    override fun read(buffer: ByteBuffer, messageNumber: Int): MessageParseResult<CreateGame> {
      if (buffer.remaining() < 8) {
        return MessageParseResult.Failure("Failed byte count validation!")
      }
      val userName = EmuUtil.readString(buffer)
      if (buffer.remaining() < 6) {
        return MessageParseResult.Failure("Failed byte count validation!")
      }
      val romName = EmuUtil.readString(buffer)
      if (buffer.remaining() < 5) {
        return MessageParseResult.Failure("Failed byte count validation!")
      }
      val clientType = EmuUtil.readString(buffer)
      if (buffer.remaining() < 4) {
        return MessageParseResult.Failure("Failed byte count validation!")
      }
      val gameID = buffer.getUnsignedShort()
      val val1 = buffer.getUnsignedShort()
      return MessageParseResult.Success(
        if (userName == REQUEST_USERNAME && gameID == REQUEST_GAME_ID && val1 == REQUEST_VAL1)
          Request(messageNumber, romName)
        else Notification(messageNumber, userName, romName, clientType, gameID, val1)
      )
    }

    override fun write(buffer: ByteBuffer, message: CreateGame) {
      EmuUtil.writeString(
        buffer,
        when (message) {
          is Request -> REQUEST_USERNAME
          is Notification -> message.username
        }
      )
      EmuUtil.writeString(buffer, message.romName)
      EmuUtil.writeString(
        buffer,
        when (message) {
          is Request -> REQUEST_CLIENT_TYPE
          is Notification -> message.clientType
        }
      )
      buffer.putUnsignedShort(
        when (message) {
          is Request -> REQUEST_GAME_ID
          is Notification -> message.gameId
        }
      )
      buffer.putUnsignedShort(
        when (message) {
          is Request -> REQUEST_VAL1
          is Notification -> message.val1
        }
      )
    }
  }
}
