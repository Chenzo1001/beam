/*
 * Copyright (C) 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.dataflow.sdk.transforms.windowing;

import com.google.cloud.dataflow.sdk.coders.AtomicCoder;
import com.google.cloud.dataflow.sdk.coders.Coder;
import com.google.cloud.dataflow.sdk.coders.CoderException;
import com.google.cloud.dataflow.sdk.transforms.GroupByKey;
import com.google.cloud.dataflow.sdk.util.VarInt;
import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;

/**
 * Provides information about the pane this value belongs to. Every pane is implicitly associated
 * with a window.
 *
 * <p>Note: This does not uniquely identify a pane, and should not be used for comparisons.
 */
public final class PaneInfo {

  /**
   * Enumerates the possibilities for how the timing of this pane firing related to the watermark.
   */
  public enum Timing {
    /** Pane was fired before the watermark passed the end of the window. */
    EARLY,
    /** First pane fired after the watermark passed the end of the window. */
    ON_TIME,
    /** Panes fired after the {@code ON_TIME} firing. */
    LATE,
    /**
     * This element was not produced in a triggered pane and its relation to the watermark is
     * unknown.
     */
    UNKNOWN;

    // NOTE: Do not add fields or re-order them. The ordinal is used as part of
    // the encoding.
  }

  private static byte encodedByte(boolean isFirst, boolean isLast, Timing timing) {
    byte result = 0x0;
    if (isFirst) {
      result |= 1;
    }
    if (isLast) {
      result |= 2;
    }
    result |= timing.ordinal() << 2;
    return result;
  }

  private static final ImmutableMap<Byte, PaneInfo> BYTE_TO_PANE_INFO;
  static {
    ImmutableMap.Builder<Byte, PaneInfo> decodingBuilder = ImmutableMap.builder();
    for (Timing timing : Timing.values()) {
      long onTimeIndex = timing == Timing.EARLY ? -1 : 0;
      register(decodingBuilder, new PaneInfo(true, true, timing, 0, onTimeIndex));
      register(decodingBuilder, new PaneInfo(true, false, timing, 0, onTimeIndex));
      register(decodingBuilder, new PaneInfo(false, true, timing, -1, onTimeIndex));
      register(decodingBuilder, new PaneInfo(false, false, timing, -1, onTimeIndex));
    }
    BYTE_TO_PANE_INFO = decodingBuilder.build();
  }

  private static void register(ImmutableMap.Builder<Byte, PaneInfo> builder, PaneInfo info) {
    builder.put(info.encodedByte, info);
  }

  private final byte encodedByte;

  private final boolean isFirst;
  private final boolean isLast;
  private final Timing timing;
  private final long index;
  private final long nonSpeculativeIndex;

  /**
   * {@code PaneInfo} to use for elements on (and before) initial window assignemnt (including
   * elements read from sources) before they have passed through a {@link GroupByKey} and are
   * associated with a particular trigger firing.
   */
  public static final PaneInfo NO_FIRING =
      PaneInfo.createPane(true, true, Timing.UNKNOWN, 0, 0);

  /**
   * {@code PaneInfo} to use when there will be exactly one firing and it is on time.
   */
  public static final PaneInfo ON_TIME_AND_ONLY_FIRING =
      PaneInfo.createPane(true, true, Timing.ON_TIME, 0, 0);

  private PaneInfo(boolean isFirst, boolean isLast, Timing timing, long index, long onTimeIndex) {
    this.encodedByte = encodedByte(isFirst, isLast, timing);
    this.isFirst = isFirst;
    this.isLast = isLast;
    this.timing = timing;
    this.index = index;
    this.nonSpeculativeIndex = onTimeIndex;
  }

  public static PaneInfo createPane(boolean isFirst, boolean isLast, Timing timing) {
    Preconditions.checkArgument(isFirst, "Indices must be provided for non-first pane info.");
    return createPane(isFirst, isLast, timing, 0, timing == Timing.EARLY ? -1 : 0);
  }

  /**
   * Factory method to create a {@link PaneInfo} with the specified parameters.
   */
  public static PaneInfo createPane(
      boolean isFirst, boolean isLast, Timing timing, long index, long onTimeIndex) {
    if (isFirst || timing == Timing.UNKNOWN) {
      return Preconditions.checkNotNull(
          BYTE_TO_PANE_INFO.get(encodedByte(isFirst, isLast, timing)));
    } else {
      return new PaneInfo(isFirst, isLast, timing, index, onTimeIndex);
    }
  }

  public static PaneInfo decodePane(byte encodedPane) {
    return Preconditions.checkNotNull(BYTE_TO_PANE_INFO.get(encodedPane));
  }

  /**
   * Return true if there is no timing information for the current {@link PaneInfo}.
   * This typically indicates that the current element has not been assigned to
   * windows or passed through an operation that executes triggers yet.
   */
  public boolean isUnknown() {
    return Timing.UNKNOWN.equals(timing);
  }

  /**
   * Return true if this is the first pane produced for the associated window.
   */
  public boolean isFirst() {
    return isFirst;
  }

  /**
   * Return true if this is the last pane that will be produced in the associated window.
   */
  public boolean isLast() {
    return isLast;
  }

  /**
   * Return true if this is the last pane that will be produced in the associated window.
   */
  public Timing getTiming() {
    return timing;
  }

  /**
   * The zero-based index of this trigger firing that produced this pane.
   *
   * <p>This will return 0 for the first time the timer fires, 1 for the next time, etc.
   *
   * <p>A given (key, window, pane-index) is guaranteed to be unique in the
   * output of a group-by-key operation.
   */
  public long getIndex() {
    return index;
  }

  /**
   * The zero-based index of this trigger firing among non-speculative panes.
   *
   * <p> This will return 0 for the first non-{@link Timing#EARLY} timer firing, 1 for the next one,
   * etc.
   *
   * <p>Always -1 for speculative data.
   */
  public long getNonSpeculativeIndex() {
    return nonSpeculativeIndex;
  }

  int getEncodedByte() {
    return encodedByte;
  }

  @Override
  public int hashCode() {
    return Objects.hash(encodedByte, index, nonSpeculativeIndex);
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      // Simple PaneInfos are interned.
      return true;
    } else if (obj instanceof PaneInfo) {
      PaneInfo that = (PaneInfo) obj;
      return this.encodedByte == that.encodedByte
          && this.index == that.index
          && this.nonSpeculativeIndex == that.nonSpeculativeIndex;
    } else {
      return false;
    }
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(getClass())
        .omitNullValues()
        .add("isFirst", isFirst ? true : null)
        .add("isLast", isLast ? true : null)
        .add("timing", timing)
        .add("index", index)
        .add("onTimeIndex", nonSpeculativeIndex != -1 ? nonSpeculativeIndex : null)
        .toString();
  }

  /**
   * A Coder for encoding PaneInfo instances.
   */
  public static class PaneInfoCoder extends AtomicCoder<PaneInfo> {
    private static enum Encoding {
      FIRST,
      ONE_INDEX,
      TWO_INDICES;

      // NOTE: Do not reorder fields. The ordinal is used as part of
      // the encoding.

      public final byte tag;

      private Encoding() {
        assert ordinal() < 16;
        tag = (byte) (ordinal() << 4);
      }

      public static Encoding fromTag(byte b) {
        return Encoding.values()[b >> 4];
      }
    }

    private Encoding chooseEncoding(PaneInfo value) {
      if (value.index == 0 && value.nonSpeculativeIndex == 0 || value.timing == Timing.UNKNOWN) {
        return Encoding.FIRST;
      } else if (value.index == value.nonSpeculativeIndex || value.timing == Timing.EARLY) {
        return Encoding.ONE_INDEX;
      } else {
        return Encoding.TWO_INDICES;
      }
    }

    public static final PaneInfoCoder INSTANCE = new PaneInfoCoder();

    @Override
    public void encode(PaneInfo value, final OutputStream outStream, Coder.Context context)
        throws CoderException, IOException {
      Encoding encoding = chooseEncoding(value);
      switch (chooseEncoding(value)) {
        case FIRST:
          outStream.write(value.encodedByte);
          break;
        case ONE_INDEX:
          outStream.write(value.encodedByte | encoding.tag);
          VarInt.encode(value.index, outStream);
          break;
        case TWO_INDICES:
          outStream.write(value.encodedByte | encoding.tag);
          VarInt.encode(value.index, outStream);
          VarInt.encode(value.nonSpeculativeIndex, outStream);
          break;
        default:
          throw new CoderException("Unknown encoding " + encoding);
      }
    }

    @Override
    public PaneInfo decode(final InputStream inStream, Coder.Context context)
        throws CoderException, IOException {
      byte keyAndTag = (byte) inStream.read();
      PaneInfo base = BYTE_TO_PANE_INFO.get((byte) (keyAndTag & 0x0F));
      long index, onTimeIndex;
      switch (Encoding.fromTag(keyAndTag)) {
        case FIRST:
          return base;
        case ONE_INDEX:
          index = VarInt.decodeLong(inStream);
          onTimeIndex = base.timing == Timing.EARLY ? -1 : index;
          break;
        case TWO_INDICES:
          index = VarInt.decodeLong(inStream);
          onTimeIndex = VarInt.decodeLong(inStream);
          break;
        default:
          throw new CoderException("Unknown encoding " + (keyAndTag & 0xF0));
      }
      return new PaneInfo(base.isFirst, base.isLast, base.timing, index, onTimeIndex);
    }
  }
}
