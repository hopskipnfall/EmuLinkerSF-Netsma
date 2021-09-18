package org.emulinker.kaillera.controller.v086.action;

import com.google.common.flogger.FluentLogger;
import org.emulinker.kaillera.controller.messaging.MessageFormatException;
import org.emulinker.kaillera.controller.v086.V086Controller;
import org.emulinker.kaillera.controller.v086.protocol.*;
import org.emulinker.kaillera.model.KailleraUser;
import org.emulinker.kaillera.model.exception.GameDataException;

public class CachedGameDataAction implements V086Action {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final String desc = "CachedGameDataAction";
  private static CachedGameDataAction singleton = new CachedGameDataAction();

  public static CachedGameDataAction getInstance() {
    return singleton;
  }

  private int actionCount = 0;

  private CachedGameDataAction() {}

  @Override
  public int getActionPerformedCount() {
    return actionCount;
  }

  @Override
  public String toString() {
    return desc;
  }

  @Override
  public void performAction(V086Message message, V086Controller.V086ClientHandler clientHandler)
      throws FatalActionException {
    try {
      KailleraUser user = clientHandler.getUser();

      int key = ((CachedGameData) message).key();
      byte[] data = clientHandler.getClientGameDataCache().get(key);

      if (data == null) {
        logger.atFine().log("Game Cache Error: null data");
        return;
      }

      user.addGameData(data);
    } catch (GameDataException e) {
      logger.atFine().withCause(e).log("Game data error");

      if (e.hasResponse()) {
        try {
          clientHandler.send(
              GameData.create(clientHandler.getNextMessageNumber(), e.getResponse()));
        } catch (MessageFormatException e2) {
          logger.atSevere().withCause(e2).log("Failed to contruct GameData message");
        }
      }
    } catch (IndexOutOfBoundsException e) {
      logger.atSevere().withCause(e).log(
          "Game data error!  The client cached key "
              + ((CachedGameData) message).key()
              + " was not found in the cache!");

      // This may not always be the best thing to do...
      try {
        clientHandler.send(
            GameChat_Notification.create(
                clientHandler.getNextMessageNumber(),
                "Error",
                "Game Data Error!  Game state will be inconsistent!"));
      } catch (MessageFormatException e2) {
        logger.atSevere().withCause(e2).log("Failed to contruct new GameChat_Notification");
      }
    }
  }
}
