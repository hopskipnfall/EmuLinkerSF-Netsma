package org.emulinker.kaillera.controller.v086.protocol

import java.nio.ByteBuffer
import org.emulinker.kaillera.controller.messaging.MessageFormatException
import org.emulinker.kaillera.controller.messaging.ParseException
import org.emulinker.kaillera.controller.v086.V086Utils.getNumBytesPlusStopByte
import org.emulinker.kaillera.pico.AppModule
import org.emulinker.util.EmuUtil
import org.emulinker.util.UnsignedUtil.getUnsignedShort
import org.emulinker.util.UnsignedUtil.putUnsignedShort

sealed class Quit : V086Message() {
  /** NOTE: May be the empty string. */
  abstract val username: String
  abstract val userId: Int
  abstract val message: String

  override val bodyLength: Int
    get() = username.getNumBytesPlusStopByte() - 1 + (message.getNumBytesPlusStopByte() - 1) + 4

  public override fun writeBodyTo(buffer: ByteBuffer) {
    EmuUtil.writeString(buffer, username, 0x00, AppModule.charsetDoNotUse)
    buffer.putUnsignedShort(userId)
    EmuUtil.writeString(buffer, message, 0x00, AppModule.charsetDoNotUse)
  }

  data class Notification
  @Throws(MessageFormatException::class)
  constructor(
    override val messageNumber: Int,
    override val username: String,
    override val userId: Int,
    override val message: String
  ) : Quit() {

    override val messageId = ID

    init {
      require(userId in 0..0xFFFF) { "UserID out of acceptable range: $userId" }
      require(username.isNotBlank()) { "Username cannot be empty" }
    }
  }

  data class Request
  @Throws(MessageFormatException::class)
  constructor(override val messageNumber: Int, override val message: String) : Quit() {

    override val username = ""
    override val userId = 0xFFFF

    override val messageId = ID
  }

  companion object {
    const val ID: Byte = 0x01

    @Throws(ParseException::class, MessageFormatException::class)
    fun parse(messageNumber: Int, buffer: ByteBuffer): MessageParseResult<Quit> {
      if (buffer.remaining() < 5) {
        return MessageParseResult.Failure("Failed byte count validation!")
      }
      val userName = EmuUtil.readString(buffer, 0x00, AppModule.charsetDoNotUse)
      if (buffer.remaining() < 3) {
        return MessageParseResult.Failure("Failed byte count validation!")
      }
      val userID = buffer.getUnsignedShort()
      val message = EmuUtil.readString(buffer, 0x00, AppModule.charsetDoNotUse)
      return MessageParseResult.Success(
        if (userName.isBlank() && userID == 0xFFFF) {
          Request(messageNumber, message)
        } else {
          Notification(messageNumber, userName, userID, message)
        }
      )
    }
  }
}
