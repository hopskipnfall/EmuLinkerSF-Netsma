package org.emulinker.kaillera.controller.v086.action;

import com.google.common.flogger.FluentLogger;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.emulinker.kaillera.controller.messaging.MessageFormatException;
import org.emulinker.kaillera.controller.v086.V086Controller;
import org.emulinker.kaillera.controller.v086.protocol.InformationMessage;
import org.emulinker.kaillera.model.event.*;

@Singleton
public class InfoMessageAction implements V086UserEventHandler {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final String DESC = "InfoMessageAction";

  private int handledCount = 0;

  @Inject
  InfoMessageAction() {}

  @Override
  public int getHandledEventCount() {
    return handledCount;
  }

  @Override
  public String toString() {
    return DESC;
  }

  @Override
  public void handleEvent(UserEvent event, V086Controller.V086ClientHandler clientHandler) {
    handledCount++;

    InfoMessageEvent infoEvent = (InfoMessageEvent) event;

    try {
      clientHandler.send(
          InformationMessage.create(
              clientHandler.getNextMessageNumber(), "server", infoEvent.getMessage()));
    } catch (MessageFormatException e) {
      logger.atSevere().withCause(e).log("Failed to contruct InformationMessage message");
    }
  }
}
