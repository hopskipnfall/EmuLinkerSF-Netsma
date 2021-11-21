package org.emulinker.kaillera.model.impl;

import com.codahale.metrics.Gauge;
import com.codahale.metrics.MetricRegistry;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.flogger.FluentLogger;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.commons.configuration.*;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.emulinker.config.RuntimeFlags;
import org.emulinker.kaillera.access.AccessManager;
import org.emulinker.kaillera.lookingforgame.LookingForGameEvent;
import org.emulinker.kaillera.lookingforgame.TwitterBroadcaster;
import org.emulinker.kaillera.master.StatsCollector;
import org.emulinker.kaillera.model.*;
import org.emulinker.kaillera.model.event.*;
import org.emulinker.kaillera.model.exception.*;
import org.emulinker.release.*;
import org.emulinker.util.*;

@Singleton
public final class KailleraServerImpl implements KailleraServer, Executable {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  protected boolean[] allowedConnectionTypes = new boolean[7];

  protected final ImmutableList<String> loginMessages;

  protected boolean stopFlag = false;
  protected boolean isRunning = false;

  protected int connectionCounter = 1;
  protected int gameCounter = 1;

  protected ThreadPoolExecutor threadPool = null;
  protected AccessManager accessManager;
  protected StatsCollector statsCollector;
  protected ReleaseInfo releaseInfo;
  protected AutoFireDetectorFactory autoFireDetectorFactory;

  protected Map<Integer, KailleraUserImpl> users;
  protected Map<Integer, KailleraGameImpl> games;

  private Trivia trivia = null;
  private Thread triviaThread;
  private boolean switchTrivia = false;

  private final RuntimeFlags flags;

  private final TwitterBroadcaster lookingForGameReporter;

  @Inject
  KailleraServerImpl(
      ThreadPoolExecutor threadPool,
      AccessManager accessManager,
      Configuration config,
      RuntimeFlags flags,
      StatsCollector statsCollector,
      ReleaseInfo releaseInfo,
      AutoFireDetectorFactory autoFireDetectorFactory,
      TwitterBroadcaster lookingForGameReporter,
      MetricRegistry metrics) {
    this.lookingForGameReporter = lookingForGameReporter;
    this.flags = flags;
    this.threadPool = threadPool;
    this.accessManager = accessManager;
    this.releaseInfo = releaseInfo;
    this.autoFireDetectorFactory = autoFireDetectorFactory;

    ImmutableList.Builder<String> loginMessagesBuilder = ImmutableList.builder();
    for (int i = 1; EmuLang.hasString("KailleraServerImpl.LoginMessage." + i); i++) {
      loginMessagesBuilder.add(EmuLang.getString("KailleraServerImpl.LoginMessage." + i));
    }
    loginMessages = loginMessagesBuilder.build();

    flags
        .connectionTypes()
        .forEach(
            type -> {
              int ct = Integer.parseInt(type);
              allowedConnectionTypes[ct] = true;
            });

    users = new ConcurrentHashMap<>(flags.maxUsers());
    games = new ConcurrentHashMap<>(flags.maxGames());

    if (flags.touchKaillera()) {
      this.statsCollector = statsCollector;
    }

    metrics.register(
        MetricRegistry.name(this.getClass(), "users", "idle"),
        new Gauge<Integer>() {
          @Override
          public Integer getValue() {
            return (int)
                users.values().stream()
                    .filter(user -> user.getStatus() == KailleraUser.STATUS_IDLE)
                    .count();
          }
        });

    metrics.register(
        MetricRegistry.name(this.getClass(), "users", "playing"),
        new Gauge<Integer>() {
          @Override
          public Integer getValue() {
            return (int)
                users.values().stream()
                    .filter(user -> user.getStatus() == KailleraUser.STATUS_PLAYING)
                    .count();
          }
        });

    metrics.register(
        MetricRegistry.name(this.getClass(), "games", "waiting"),
        new Gauge<Integer>() {
          @Override
          public Integer getValue() {
            return (int)
                games.values().stream()
                    .filter(game -> game.getStatus() == KailleraGame.STATUS_WAITING)
                    .count();
          }
        });

    metrics.register(
        MetricRegistry.name(this.getClass(), "games", "playing"),
        new Gauge<Integer>() {
          @Override
          public Integer getValue() {
            return (int)
                games.values().stream()
                    .filter(game -> game.getStatus() == KailleraGame.STATUS_PLAYING)
                    .count();
          }
        });
  }

  @Override
  public void setTrivia(Trivia trivia) {
    this.trivia = trivia;
  }

  public void setTriviaThread(Thread triviaThread) {
    this.triviaThread = triviaThread;
  }

  @Override
  public void setSwitchTrivia(boolean switchTrivia) {
    this.switchTrivia = switchTrivia;
  }

  @Override
  public Trivia getTrivia() {
    return trivia;
  }

  public Thread getTriviaThread() {
    return triviaThread;
  }

  @Override
  public boolean getSwitchTrivia() {
    return switchTrivia;
  }

  @Override
  public AccessManager getAccessManager() {
    return accessManager;
  }

  @Override
  public KailleraUser getUser(int userID) {
    return users.get(userID);
  }

  @Override
  public KailleraGame getGame(int gameID) {
    return games.get(gameID);
  }

  @Override
  public Collection<KailleraUserImpl> getUsers() {
    return users.values();
  }

  @Override
  public Collection<KailleraGameImpl> getGames() {
    return games.values();
  }

  @Override
  public int getNumUsers() {
    return users.size();
  }

  @Override
  public int getNumGames() {
    return games.size();
  }

  public int getNumGamesPlaying() {
    int count = 0;
    for (KailleraGameImpl game : getGames()) {
      if (game.getStatus() != KailleraGame.STATUS_WAITING) count++;
    }
    return count;
  }

  @Override
  public int getMaxPing() {
    return flags.maxPing();
  }

  @Override
  public int getMaxUsers() {
    return flags.maxUsers();
  }

  @Override
  public int getMaxGames() {
    return flags.maxGames();
  }

  @Override
  public boolean isRunning() {
    return isRunning;
  }

  protected int getChatFloodTime() {
    return flags.chatFloodTime();
  }

  protected int getCreateGameFloodTime() {
    return flags.createGameFloodTime();
  }

  protected boolean getAllowSinglePlayer() {
    return flags.allowSinglePlayer();
  }

  protected int getMaxUserNameLength() {
    return flags.maxUserNameLength();
  }

  protected int getMaxChatLength() {
    return flags.maxChatLength();
  }

  protected int getMaxGameChatLength() {
    return flags.maxGameChatLength();
  }

  protected int getMaxGameNameLength() {
    return flags.maxGameNameLength();
  }

  protected int getQuitMessageLength() {
    return flags.maxQuitMessageLength();
  }

  protected int getMaxClientNameLength() {
    return flags.maxClientNameLength();
  }

  protected boolean getAllowMultipleConnections() {
    return flags.allowMultipleConnections();
  }

  public ThreadPoolExecutor getThreadPool() {
    return threadPool;
  }

  @Override
  public String toString() {
    return String.format(
        "KailleraServerImpl[numUsers=%d numGames=%d isRunning=%b]",
        getNumUsers(), getNumGames(), isRunning());
  }

  @Override
  public synchronized void start() {
    logger.atFine().log("KailleraServer thread received start request!");
    logger.atFine().log(
        "KailleraServer thread starting (ThreadPool:%d/%d)",
        threadPool.getActiveCount(), threadPool.getPoolSize());
    stopFlag = false;
    threadPool.execute(this);
    Thread.yield();
  }

  @Override
  public synchronized void stop() {
    logger.atFine().log("KailleraServer thread received stop request!");

    if (!isRunning()) {
      logger.atFine().log("KailleraServer thread stop request ignored: not running!");
      return;
    }

    stopFlag = true;

    for (KailleraUserImpl user : users.values()) user.stop();

    users.clear();
    games.clear();
  }

  // not synchronized because I know the caller will be thread safe
  protected int getNextUserID() {
    if (connectionCounter > 0xFFFF) connectionCounter = 1;

    return connectionCounter++;
  }

  // not synchronized because I know the caller will be thread safe
  protected int getNextGameID() {
    if (gameCounter > 0xFFFF) gameCounter = 1;

    return gameCounter++;
  }

  protected StatsCollector getStatsCollector() {
    return statsCollector;
  }

  protected AutoFireDetector getAutoFireDetector(KailleraGame game) {
    return autoFireDetectorFactory.getInstance(game, flags.gameAutoFireSensitivity());
  }

  @Override
  public ReleaseInfo getReleaseInfo() {
    return releaseInfo;
  }

  @Override
  public synchronized KailleraUser newConnection(
      InetSocketAddress clientSocketAddress, String protocol, KailleraEventListener listener)
      throws ServerFullException, NewConnectionException {
    // we'll assume at this point that ConnectController has already asked AccessManager if this IP
    // is banned, so no need to do it again here

    logger.atFine().log(
        "Processing connection request from " + EmuUtil.formatSocketAddress(clientSocketAddress));

    int access = accessManager.getAccess(clientSocketAddress.getAddress());

    // admins will be allowed in even if the server is full
    if (flags.maxUsers() > 0
        && users.size() >= getMaxUsers()
        && !(access > AccessManager.ACCESS_NORMAL)) {
      logger.atWarning().log(
          "Connection from "
              + EmuUtil.formatSocketAddress(clientSocketAddress)
              + " denied: Server is full!");
      throw new ServerFullException(EmuLang.getString("KailleraServerImpl.LoginDeniedServerFull"));
    }

    int userID = getNextUserID();
    KailleraUserImpl user =
        new KailleraUserImpl(userID, protocol, clientSocketAddress, listener, this);
    user.setStatus(KailleraUser.STATUS_CONNECTING);

    logger.atInfo().log(
        user
            + " attempting new connection using protocol "
            + protocol
            + " from "
            + EmuUtil.formatSocketAddress(clientSocketAddress));

    logger.atFine().log(
        user
            + " Thread starting (ThreadPool:"
            + threadPool.getActiveCount()
            + "/"
            + threadPool.getPoolSize()
            + ")");
    threadPool.execute(user);
    Thread.yield();
    logger.atFine().log(
        user
            + " Thread started (ThreadPool:"
            + threadPool.getActiveCount()
            + "/"
            + threadPool.getPoolSize()
            + ")");
    users.put(userID, user);

    return user;
  }

  @Override
  public synchronized void login(KailleraUser user)
      throws PingTimeException, ClientAddressException, ConnectionTypeException, UserNameException,
          LoginException {
    KailleraUserImpl userImpl = (KailleraUserImpl) user;

    long loginDelay = (System.currentTimeMillis() - user.getConnectTime());
    logger.atInfo().log(
        user
            + ": login request: delay="
            + loginDelay
            + "ms, clientAddress="
            + EmuUtil.formatSocketAddress(user.getSocketAddress())
            + ", name="
            + user.getName()
            + ", ping="
            + user.getPing()
            + ", client="
            + user.getClientType()
            + ", connection="
            + KailleraUser.CONNECTION_TYPE_NAMES[user.getConnectionType()]);

    if (user.isLoggedIn()) {
      logger.atWarning().log(user + " login denied: Already logged in!");
      throw new LoginException(EmuLang.getString("KailleraServerImpl.LoginDeniedAlreadyLoggedIn"));
    }

    Integer userListKey = user.getID();
    KailleraUser u = users.get(userListKey);
    if (u == null) {
      logger.atWarning().log(user + " login denied: Connection timed out!");
      throw new LoginException(
          EmuLang.getString("KailleraServerImpl.LoginDeniedConnectionTimedOut"));
    }

    int access = accessManager.getAccess(user.getSocketAddress().getAddress());
    if (access < AccessManager.ACCESS_NORMAL) {
      logger.atInfo().log(user + " login denied: Access denied");
      users.remove(userListKey);
      throw new LoginException(EmuLang.getString("KailleraServerImpl.LoginDeniedAccessDenied"));
    }

    if (access == AccessManager.ACCESS_NORMAL
        && getMaxPing() > 0
        && user.getPing() > getMaxPing()) {
      logger.atInfo().log(user + " login denied: Ping " + user.getPing() + " > " + getMaxPing());
      users.remove(userListKey);
      throw new PingTimeException(
          EmuLang.getString(
              "KailleraServerImpl.LoginDeniedPingTooHigh",
              (user.getPing() + " > " + getMaxPing())));
    }

    if (access == AccessManager.ACCESS_NORMAL
        && allowedConnectionTypes[user.getConnectionType()] == false) {
      logger.atInfo().log(
          user
              + " login denied: Connection "
              + KailleraUser.CONNECTION_TYPE_NAMES[user.getConnectionType()]
              + " Not Allowed");
      users.remove(userListKey);
      throw new LoginException(
          EmuLang.getString(
              "KailleraServerImpl.LoginDeniedConnectionTypeDenied",
              KailleraUser.CONNECTION_TYPE_NAMES[user.getConnectionType()]));
    }

    if (user.getPing() < 0) {
      logger.atWarning().log(user + " login denied: Invalid ping: " + user.getPing());
      users.remove(userListKey);
      throw new PingTimeException(
          EmuLang.getString("KailleraServerImpl.LoginErrorInvalidPing", user.getPing()));
    }

    if (access == AccessManager.ACCESS_NORMAL && Strings.isNullOrEmpty(user.getName())
        || user.getName().isBlank()) {
      logger.atInfo().log(user + " login denied: Empty UserName");
      users.remove(userListKey);
      throw new UserNameException(EmuLang.getString("KailleraServerImpl.LoginDeniedUserNameEmpty"));
    }

    // new SF MOD - Username filter
    if ((user.getName().equals("Server") || user.getName().toLowerCase().contains("|"))
        || (access == AccessManager.ACCESS_NORMAL
            && (user.getName().toLowerCase().contains("www.")
                || user.getName().toLowerCase().contains("http://")
                || user.getName().toLowerCase().contains("https://")
                || user.getName().toLowerCase().contains("\\")
                || user.getName().toLowerCase().contains("�")
                || user.getName().toLowerCase().contains("�")))) {
      logger.atInfo().log(user + " login denied: Illegal characters in UserName");
      users.remove(userListKey);
      throw new UserNameException(
          EmuLang.getString("KailleraServerImpl.LoginDeniedIllegalCharactersInUserName"));
    }

    // access == AccessManager.ACCESS_NORMAL &&
    if (flags.maxUserNameLength() > 0 && user.getName().length() > getMaxUserNameLength()) {
      logger.atInfo().log(user + " login denied: UserName Length > " + getMaxUserNameLength());
      users.remove(userListKey);
      throw new UserNameException(
          EmuLang.getString("KailleraServerImpl.LoginDeniedUserNameTooLong"));
    }

    if (access == AccessManager.ACCESS_NORMAL
        && flags.maxClientNameLength() > 0
        && user.getClientType().length() > getMaxClientNameLength()) {
      logger.atInfo().log(user + " login denied: Client Name Length > " + getMaxClientNameLength());
      users.remove(userListKey);
      throw new UserNameException(
          EmuLang.getString("KailleraServerImpl.LoginDeniedEmulatorNameTooLong"));
    }

    if (user.getClientType().toLowerCase().contains("|")) {
      logger.atWarning().log(user + " login denied: Illegal characters in EmulatorName");
      users.remove(userListKey);
      throw new UserNameException("Illegal characters in Emulator Name");
    }

    if (access == AccessManager.ACCESS_NORMAL) {
      char[] chars = user.getName().toCharArray();
      for (int i = 0; i < chars.length; i++) {
        if (chars[i] < 32) {
          logger.atInfo().log(user + " login denied: Illegal characters in UserName");
          users.remove(userListKey);
          throw new UserNameException(
              EmuLang.getString("KailleraServerImpl.LoginDeniedIllegalCharactersInUserName"));
        }
      }
    }

    if (u.getStatus() != KailleraUser.STATUS_CONNECTING) {
      users.remove(userListKey);
      logger.atWarning().log(
          user + " login denied: Invalid status=" + KailleraUser.STATUS_NAMES[u.getStatus()]);
      throw new LoginException(
          EmuLang.getString("KailleraServerImpl.LoginErrorInvalidStatus", u.getStatus()));
    }

    if (!u.getConnectSocketAddress().getAddress().equals(user.getSocketAddress().getAddress())) {
      users.remove(userListKey);
      logger.atWarning().log(
          user
              + " login denied: Connect address does not match login address: "
              + u.getConnectSocketAddress().getAddress().getHostAddress()
              + " != "
              + user.getSocketAddress().getAddress().getHostAddress());
      throw new ClientAddressException(
          EmuLang.getString("KailleraServerImpl.LoginDeniedAddressMatchError"));
    }

    if (access == AccessManager.ACCESS_NORMAL
        && !accessManager.isEmulatorAllowed(user.getClientType())) {
      logger.atInfo().log(
          user + " login denied: AccessManager denied emulator: " + user.getClientType());
      users.remove(userListKey);
      throw new LoginException(
          EmuLang.getString(
              "KailleraServerImpl.LoginDeniedEmulatorRestricted", user.getClientType()));
    }

    for (KailleraUserImpl u2 : getUsers()) {
      if (u2.isLoggedIn()) {
        if (!u2.equals(u)
            && u.getConnectSocketAddress()
                .getAddress()
                .equals(u2.getConnectSocketAddress().getAddress())
            && u.getName().equals(u2.getName())) {
          // user is attempting to login more than once with the same name and address
          // logoff the old user and login the new one

          try {
            quit(u2, EmuLang.getString("KailleraServerImpl.ForcedQuitReconnected"));
          } catch (Exception e) {
            logger.atSevere().withCause(e).log("Error forcing " + u2 + " quit for reconnect!");
          }
        } else if (!u2.equals(u)
            && u2.getName().toLowerCase().trim().equals(u.getName().toLowerCase().trim())) {
          users.remove(userListKey);
          logger.atWarning().log(
              user + " login denied: Duplicating Names is not allowed! " + u2.getName());
          throw new ClientAddressException("Duplicating names is not allowed: " + u2.getName());
        }

        if (access == AccessManager.ACCESS_NORMAL
            && !u2.equals(u)
            && u.getConnectSocketAddress()
                .getAddress()
                .equals(u2.getConnectSocketAddress().getAddress())
            && !u.getName().equals(u2.getName())
            && !flags.allowMultipleConnections()) {
          users.remove(userListKey);
          logger.atWarning().log(
              user + " login denied: Address already logged in as " + u2.getName());
          throw new ClientAddressException(
              EmuLang.getString("KailleraServerImpl.LoginDeniedAlreadyLoggedInAs", u2.getName()));
        }
      }
    }

    // passed all checks

    userImpl.setAccess(access);
    userImpl.setStatus(KailleraUser.STATUS_IDLE);
    userImpl.setLoggedIn();
    users.put(userListKey, userImpl);
    userImpl.addEvent(new ConnectedEvent(this, user));
    try {
      Thread.sleep(20);
    } catch (Exception e) {
    }

    for (String loginMessage : loginMessages) {
      userImpl.addEvent(new InfoMessageEvent(user, loginMessage));
      try {
        Thread.sleep(20);
      } catch (Exception e) {
      }
    }

    if (access > AccessManager.ACCESS_NORMAL)
      logger.atInfo().log(
          user + " logged in successfully with " + AccessManager.ACCESS_NAMES[access] + " access!");
    else logger.atInfo().log(user + " logged in successfully");

    // this is fairly ugly
    if (user.isEmuLinkerClient()) {
      userImpl.addEvent(new InfoMessageEvent(user, ":ACCESS=" + userImpl.getAccessStr()));

      if (access >= AccessManager.ACCESS_SUPERADMIN) {
        StringBuilder sb = new StringBuilder();
        sb.append(":USERINFO=");
        int sbCount = 0;
        for (KailleraUserImpl u3 : getUsers()) {
          if (!u3.isLoggedIn()) continue;

          sb.append(u3.getID());
          sb.append((char) 0x02);
          sb.append(u3.getConnectSocketAddress().getAddress().getHostAddress());
          sb.append((char) 0x02);
          sb.append(u3.getAccessStr());
          sb.append((char) 0x02);
          // str = u3.getName().replace(',','.');
          // str = str.replace(';','.');
          sb.append(u3.getName());
          sb.append((char) 0x02);
          sb.append(u3.getPing());
          sb.append((char) 0x02);
          sb.append(u3.getStatus());
          sb.append((char) 0x02);
          sb.append(u3.getConnectionType());
          sb.append((char) 0x03);
          sbCount++;

          if (sb.length() > 300) {
            ((KailleraUserImpl) user).addEvent(new InfoMessageEvent(user, sb.toString()));
            sb = new StringBuilder();
            sb.append(":USERINFO=");
            sbCount = 0;
            try {
              Thread.sleep(100);
            } catch (Exception e) {
            }
          }
        }
        if (sbCount > 0)
          ((KailleraUserImpl) user).addEvent(new InfoMessageEvent(user, sb.toString()));
        try {
          Thread.sleep(100);
        } catch (Exception e) {
        }
      }
    }

    try {
      Thread.sleep(20);
    } catch (Exception e) {
    }
    if (access >= AccessManager.ACCESS_ADMIN)
      userImpl.addEvent(
          new InfoMessageEvent(user, EmuLang.getString("KailleraServerImpl.AdminWelcomeMessage")));

    try {
      Thread.sleep(20);
    } catch (Exception e) {
    }
    // TODO(nue): Localize this welcome message?
    // userImpl.addEvent(
    //     new InfoMessageEvent(
    //         user,
    //         getReleaseInfo().getProductName()
    //             + " v"
    //             + getReleaseInfo().getVersionString()
    //             + ": "
    //             + getReleaseInfo().getReleaseDate()
    //             + " - Visit: www.EmuLinker.org"));

    try {
      Thread.sleep(20);
    } catch (Exception e) {
    }
    addEvent(new UserJoinedEvent(this, user));

    try {
      Thread.sleep(20);
    } catch (Exception e) {
    }
    String announcement = accessManager.getAnnouncement(user.getSocketAddress().getAddress());
    if (announcement != null) announce(announcement, false, null);
  }

  @Override
  public synchronized void quit(KailleraUser user, String message)
      throws QuitException, DropGameException, QuitGameException, CloseGameException {
    lookingForGameReporter.cancelActionsForUser(user.getID());

    if (!user.isLoggedIn()) {
      users.remove(user.getID());
      logger.atSevere().log(user + " quit failed: Not logged in");
      throw new QuitException(EmuLang.getString("KailleraServerImpl.NotLoggedIn"));
    }

    if (users.remove(user.getID()) == null)
      logger.atSevere().log(user + " quit failed: not in user list");

    KailleraGameImpl userGame = ((KailleraUserImpl) user).getGame();
    if (userGame != null) user.quitGame();

    String quitMsg = message.trim();
    if (Strings.isNullOrEmpty(quitMsg)
        || (flags.maxQuitMessageLength() > 0 && quitMsg.length() > flags.maxQuitMessageLength()))
      quitMsg = EmuLang.getString("KailleraServerImpl.StandardQuitMessage");

    int access =
        user.getServer().getAccessManager().getAccess(user.getSocketAddress().getAddress());
    if (access < AccessManager.ACCESS_SUPERADMIN
        && user.getServer().getAccessManager().isSilenced(user.getSocketAddress().getAddress())) {
      quitMsg = "www.EmuLinker.org";
    }

    logger.atInfo().log(user + " quit: " + quitMsg);

    UserQuitEvent quitEvent = new UserQuitEvent(this, user, quitMsg);

    addEvent(quitEvent);
    ((KailleraUserImpl) user).addEvent(quitEvent);
  }

  @Override
  public synchronized void chat(KailleraUser user, String message)
      throws ChatException, FloodException {
    if (!user.isLoggedIn()) {
      logger.atSevere().log(user + " chat failed: Not logged in");
      throw new ChatException(EmuLang.getString("KailleraServerImpl.NotLoggedIn"));
    }

    int access = accessManager.getAccess(user.getSocketAddress().getAddress());
    if (access < AccessManager.ACCESS_SUPERADMIN
        && accessManager.isSilenced(user.getSocketAddress().getAddress())) {
      logger.atWarning().log(user + " chat denied: Silenced: " + message);
      throw new ChatException(EmuLang.getString("KailleraServerImpl.ChatDeniedSilenced"));
    }

    if (access == AccessManager.ACCESS_NORMAL
        && flags.chatFloodTime() > 0
        && (System.currentTimeMillis() - ((KailleraUserImpl) user).getLastChatTime())
            < (flags.chatFloodTime() * 1000)) {
      logger.atWarning().log(user + " chat denied: Flood: " + message);
      throw new FloodException(EmuLang.getString("KailleraServerImpl.ChatDeniedFloodControl"));
    }

    if (message.equals(":USER_COMMAND")) {
      return;
    }

    message = message.trim();
    if (Strings.isNullOrEmpty(message) || message.startsWith("�") || message.startsWith("�"))
      return;

    if (access == AccessManager.ACCESS_NORMAL) {
      char[] chars = message.toCharArray();
      for (int i = 0; i < chars.length; i++) {
        if (chars[i] < 32) {
          logger.atWarning().log(user + " chat denied: Illegal characters in message");
          throw new ChatException(
              EmuLang.getString("KailleraServerImpl.ChatDeniedIllegalCharacters"));
        }
      }

      if (flags.maxChatLength() > 0 && message.length() > flags.maxChatLength()) {
        logger.atWarning().log(user + " chat denied: Message Length > " + flags.maxChatLength());
        throw new ChatException(EmuLang.getString("KailleraServerImpl.ChatDeniedMessageTooLong"));
      }
    }

    logger.atInfo().log(user + " chat: " + message);

    addEvent(new ChatEvent(this, user, message));

    if (switchTrivia) {
      if (!trivia.isAnswered() && trivia.isCorrect(message)) {
        trivia.addScore(
            user.getName(), user.getSocketAddress().getAddress().getHostAddress(), message);
      }
    }
  }

  @Override
  public synchronized KailleraGame createGame(KailleraUser user, String romName)
      throws CreateGameException, FloodException {
    if (!user.isLoggedIn()) {
      logger.atSevere().log(user + " create game failed: Not logged in");
      throw new CreateGameException(EmuLang.getString("KailleraServerImpl.NotLoggedIn"));
    }

    if (((KailleraUserImpl) user).getGame() != null) {
      logger.atSevere().log(
          user + " create game failed: already in game: " + ((KailleraUserImpl) user).getGame());
      throw new CreateGameException(
          EmuLang.getString("KailleraServerImpl.CreateGameErrorAlreadyInGame"));
    }

    if (flags.maxGameNameLength() > 0 && romName.trim().length() > flags.maxGameNameLength()) {
      logger.atWarning().log(
          user + " create game denied: Rom Name Length > " + flags.maxGameNameLength());
      throw new CreateGameException(
          EmuLang.getString("KailleraServerImpl.CreateGameDeniedNameTooLong"));
    }

    if (romName.toLowerCase().contains("|")) {
      logger.atWarning().log(user + " create game denied: Illegal characters in ROM name");
      throw new CreateGameException(
          EmuLang.getString("KailleraServerImpl.CreateGameDeniedIllegalCharacters"));
    }

    int access = accessManager.getAccess(user.getSocketAddress().getAddress());
    if (access == AccessManager.ACCESS_NORMAL) {
      if (flags.createGameFloodTime() > 0
          && (System.currentTimeMillis() - ((KailleraUserImpl) user).getLastCreateGameTime())
              < (flags.createGameFloodTime() * 1000)) {
        logger.atWarning().log(user + " create game denied: Flood: " + romName);
        throw new FloodException(
            EmuLang.getString("KailleraServerImpl.CreateGameDeniedFloodControl"));
      }

      if (flags.maxGames() > 0 && getNumGames() >= flags.maxGames()) {
        logger.atWarning().log(
            user + " create game denied: Over maximum of " + flags.maxGames() + " current games!");
        throw new CreateGameException(
            EmuLang.getString("KailleraServerImpl.CreateGameDeniedMaxGames", flags.maxGames()));
      }

      char[] chars = romName.toCharArray();
      for (int i = 0; i < chars.length; i++) {
        if (chars[i] < 32) {
          logger.atWarning().log(user + " create game denied: Illegal characters in ROM name");
          throw new CreateGameException(
              EmuLang.getString("KailleraServerImpl.CreateGameDeniedIllegalCharacters"));
        }
      }

      if (romName.trim().length() == 0) {
        logger.atWarning().log(user + " create game denied: Rom Name Empty");
        throw new CreateGameException(
            EmuLang.getString("KailleraServerImpl.CreateGameErrorEmptyName"));
      }

      if (!accessManager.isGameAllowed(romName)) {
        logger.atWarning().log(user + " create game denied: AccessManager denied game: " + romName);
        throw new CreateGameException(
            EmuLang.getString("KailleraServerImpl.CreateGameDeniedGameBanned"));
      }
    }

    KailleraGameImpl game = null;

    int gameID = getNextGameID();
    game =
        new KailleraGameImpl(
            gameID,
            romName,
            (KailleraUserImpl) user,
            this,
            flags.gameBufferSize(),
            flags.gameTimeoutMillis(),
            flags.gameDesynchTimeouts());
    games.put(gameID, game);

    addEvent(new GameCreatedEvent(this, game));

    logger.atInfo().log(user + " created: " + game + ": " + game.getRomName());

    try {
      user.joinGame(game.getID());
    } catch (Exception e) {
      // this shouldn't happen
      logger.atSevere().withCause(e).log(
          "Caught exception while making owner join game! This shouldn't happen!");
    }

    announce(
        EmuLang.getString(
            "KailleraServerImpl.UserCreatedGameAnnouncement", user.getName(), game.getRomName()),
        false,
        null);

    if (flags.twitterEnabled()) {
      // TODO(nue): Suppress logic if username contains @待ち or something.
      lookingForGameReporter.reportAndStartTimer(
          LookingForGameEvent.builder()
              .setGameId(game.getID())
              .setGameTitle(game.getRomName())
              .setUserId(user.getID())
              .setUsername(user.getName())
              .build());

      announceInGame(
          EmuLang.getString(
              "KailleraServerImpl.TweetPendingAnnouncement",
              flags.twitterBroadcastDelay().getSeconds()),
          // TODO(nue): Remove the need to use impl here..
          (KailleraUserImpl) user);
    }
    return game;
  }

  synchronized void closeGame(KailleraGame game, KailleraUser user) throws CloseGameException {
    if (!user.isLoggedIn()) {
      logger.atSevere().log(user + " close " + game + " failed: Not logged in");
      throw new CloseGameException(EmuLang.getString("KailleraServerImpl.NotLoggedIn"));
    }

    if (!games.containsKey(game.getID())) {
      logger.atSevere().log(user + " close " + game + " failed: not in list: " + game);
      return;
    }

    ((KailleraGameImpl) game).close(user);
    games.remove(game.getID());

    logger.atInfo().log(user + " closed: " + game);
    addEvent(new GameClosedEvent(this, game));
  }

  @Override
  public boolean checkMe(KailleraUser user, String message) {
    // >>>>>>>>>>>>>>>>>>>>
    if (!user.isLoggedIn()) {
      logger.atSevere().log(user + " chat failed: Not logged in");
      return false;
    }

    int access = accessManager.getAccess(user.getSocketAddress().getAddress());
    if (access < AccessManager.ACCESS_SUPERADMIN
        && accessManager.isSilenced(user.getSocketAddress().getAddress())) {
      logger.atWarning().log(user + " /me: Silenced: " + message);
      return false;
    }

    // if (access == AccessManager.ACCESS_NORMAL && flags.chatFloodTime() > 0 &&
    // (System.currentTimeMillis()
    // - ((KailleraUserImpl) user).getLastChatTime()) < (flags.chatFloodTime() * 1000))
    // {
    //	logger.atWarning().log(user + " /me denied: Flood: " + message);
    //	return false;
    // }

    if (message.equals(":USER_COMMAND")) {
      return false;
    }

    message = message.trim();
    if (Strings.isNullOrEmpty(message)) return false;

    if (access == AccessManager.ACCESS_NORMAL) {
      char[] chars = message.toCharArray();
      for (int i = 0; i < chars.length; i++) {
        if (chars[i] < 32) {
          logger.atWarning().log(user + " /me: Illegal characters in message");
          return false;
        }
      }

      if (flags.maxChatLength() > 0 && message.length() > flags.maxChatLength()) {
        logger.atWarning().log(user + " /me denied: Message Length > " + flags.maxChatLength());
        return false;
      }
    }

    return true;
  }

  public void announceInGame(String announcement, KailleraUserImpl user) {
    user.getGame().announce(announcement, user);
  }

  @Override
  public void announce(String announcement, boolean gamesAlso, @Nullable KailleraUserImpl user) {
    if (user != null) {
      if (gamesAlso) { //   /msg and /me commands
        for (KailleraUserImpl kailleraUser : getUsers()) {
          if (kailleraUser.isLoggedIn()) {
            int access = accessManager.getAccess(user.getConnectSocketAddress().getAddress());
            if (access < AccessManager.ACCESS_ADMIN) {
              if (!kailleraUser.searchIgnoredUsers(
                  user.getConnectSocketAddress().getAddress().getHostAddress()))
                kailleraUser.addEvent(new InfoMessageEvent(kailleraUser, announcement));
            } else {
              kailleraUser.addEvent(new InfoMessageEvent(kailleraUser, announcement));
            }

            /*//SF MOD
            if(gamesAlso){
            	if(kailleraUser.getGame() != null){
            		kailleraUser.getGame().announce(announcement, kailleraUser);
            		Thread.yield();
            	}
            }
            */
          }
        }
      } else {
        user.addEvent(new InfoMessageEvent(user, announcement));
      }
    } else {
      for (KailleraUserImpl kailleraUser : getUsers()) {
        if (kailleraUser.isLoggedIn()) {
          kailleraUser.addEvent(new InfoMessageEvent(kailleraUser, announcement));

          // SF MOD
          if (gamesAlso) {
            if (kailleraUser.getGame() != null) {
              kailleraUser.getGame().announce(announcement, kailleraUser);
              Thread.yield();
            }
          }
        }
      }
    }
  }

  protected void addEvent(ServerEvent event) {
    for (KailleraUserImpl user : users.values()) {
      if (user.isLoggedIn()) {
        if (user.getStatus() != KailleraUser.STATUS_IDLE) {
          if (user.getP2P()) {
            if (event.toString().equals("GameDataEvent")) user.addEvent(event);
            else if (event.toString().equals("ChatEvent")) continue;
            else if (event.toString().equals("UserJoinedEvent")) continue;
            else if (event.toString().equals("UserQuitEvent")) continue;
            else if (event.toString().equals("GameStatusChangedEvent")) continue;
            else if (event.toString().equals("GameClosedEvent")) continue;
            else if (event.toString().equals("GameCreatedEvent")) continue;
            else user.addEvent(event);
          } else {
            user.addEvent(event);
          }
        } else {
          user.addEvent(event);
        }
      } else {
        logger.atFine().log(user + ": not adding event, not logged in: " + event);
      }
    }
  }

  @Override
  public void run() {
    isRunning = true;
    logger.atFine().log("KailleraServer thread running...");

    try {
      while (!stopFlag) {
        try {
          Thread.sleep((long) (flags.maxPing() * 3));
        } catch (InterruptedException e) {
          logger.atSevere().withCause(e).log("Sleep Interrupted!");
        }

        //				logger.atFine().log(this + " running maintenance...");

        if (stopFlag) break;

        if (users.isEmpty()) continue;

        for (KailleraUserImpl user : getUsers()) {
          synchronized (user) {
            int access = accessManager.getAccess(user.getConnectSocketAddress().getAddress());
            ((KailleraUserImpl) user).setAccess(access);

            // LagStat
            if (user.isLoggedIn()) {
              if (user.getGame() != null
                  && user.getGame().getStatus() == KailleraGame.STATUS_PLAYING
                  && !user.getGame().getStartTimeout()) {
                if (System.currentTimeMillis() - user.getGame().getStartTimeoutTime() > 15000) {
                  user.getGame().setStartTimeout(true);
                }
              }
            }

            if (!user.isLoggedIn()
                && (System.currentTimeMillis() - user.getConnectTime()) > (flags.maxPing() * 15)) {
              logger.atInfo().log(user + " connection timeout!");
              user.stop();
              users.remove(user.getID());
            } else if (user.isLoggedIn()
                && (System.currentTimeMillis() - user.getLastKeepAlive())
                    > (flags.keepAliveTimeout() * 1000)) {
              logger.atInfo().log(user + " keepalive timeout!");
              try {
                quit(user, EmuLang.getString("KailleraServerImpl.ForcedQuitPingTimeout"));
              } catch (Exception e) {
                logger.atSevere().withCause(e).log(
                    "Error forcing " + user + " quit for keepalive timeout!");
              }
            } else if (flags.idleTimeout() > 0
                && access == AccessManager.ACCESS_NORMAL
                && user.isLoggedIn()
                && (System.currentTimeMillis() - user.getLastActivity())
                    > (flags.idleTimeout() * 1000)) {
              logger.atInfo().log(user + " inactivity timeout!");
              try {
                quit(user, EmuLang.getString("KailleraServerImpl.ForcedQuitInactivityTimeout"));
              } catch (Exception e) {
                logger.atSevere().withCause(e).log(
                    "Error forcing " + user + " quit for inactivity timeout!");
              }
            } else if (user.isLoggedIn() && access < AccessManager.ACCESS_NORMAL) {
              logger.atInfo().log(user + " banned!");
              try {
                quit(user, EmuLang.getString("KailleraServerImpl.ForcedQuitBanned"));
              } catch (Exception e) {
                logger.atSevere().withCause(e).log(
                    "Error forcing " + user + " quit because banned!");
              }
            } else if (user.isLoggedIn()
                && access == AccessManager.ACCESS_NORMAL
                && !accessManager.isEmulatorAllowed(user.getClientType())) {
              logger.atInfo().log(user + ": emulator restricted!");
              try {
                quit(user, EmuLang.getString("KailleraServerImpl.ForcedQuitEmulatorRestricted"));
              } catch (Exception e) {
                logger.atSevere().withCause(e).log(
                    "Error forcing " + user + " quit because emulator restricted!");
              }
            }
          }
        }
      }
    } catch (Throwable e) {
      if (!stopFlag) {
        logger.atSevere().withCause(e).log(
            "KailleraServer thread caught unexpected exception: " + e);
      }
    } finally {
      isRunning = false;
      logger.atFine().log("KailleraServer thread exiting...");
    }
  }
}
