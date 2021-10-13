package com.turn.ttorrent.common;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

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
