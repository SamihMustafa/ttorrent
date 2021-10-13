package com.turn.ttorrent.common;

import org.jetbrains.annotations.Nullable;

public final class TorrentLoggerFactory {

  @Nullable
  private static volatile String staticLoggersName = null;

  private static final DummyLogger logger = new DummyLogger();

  public static DummyLogger getLogger(Class<?> clazz) {
    return logger;
  }

  public static void setStaticLoggersName(@Nullable String staticLoggersName) {
    TorrentLoggerFactory.staticLoggersName = staticLoggersName;
  }
}
