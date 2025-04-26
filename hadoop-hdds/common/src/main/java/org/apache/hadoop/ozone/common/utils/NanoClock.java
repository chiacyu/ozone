package org.apache.hadoop.ozone.common.utils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

public class NanoClock extends Clock {
  private final ZoneId zone;
  private final long initialNanos;
  private final Instant initialInstant;

  public NanoClock(ZoneId zone) {
    this.zone = zone;
    this.initialNanos = System.nanoTime();
    this.initialInstant = Instant.ofEpochMilli(System.currentTimeMillis());
  }

  @Override
  public ZoneId getZone() {
    return zone;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return null;
  }

  @Override
  public Instant instant() {
    long currentNanos = System.nanoTime();
    long nanoDiff = currentNanos - initialNanos;
    // Convert nanosecond difference to seconds and nanos
    long seconds = nanoDiff / 1_000_000_000;
    int nanos = (int) (nanoDiff % 1_000_000_000);
    if (nanos < 0) {
      seconds--;
      nanos += 1_000_000_000;
    }
    return initialInstant.plusSeconds(seconds).plusNanos(nanos);
  }

  @Override
  public long millis() {
    return instant().toEpochMilli();
  }

  // Factory methods to replace Clock.system* methods
  public static NanoClock system(ZoneId zone) {
    return new NanoClock(zone);
  }

  public static NanoClock systemDefaultZone() {
    return new NanoClock(ZoneId.systemDefault());
  }

  public static NanoClock systemUTC() {
    return new NanoClock(ZoneId.of("UTC"));
  }
}
