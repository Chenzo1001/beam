/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.util.construction;

import static org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.base.Preconditions.checkArgument;

import java.util.Collections;
import java.util.List;
import org.apache.beam.model.pipeline.v1.SchemaApi;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.coders.IterableCoder;
import org.apache.beam.sdk.coders.KvCoder;
import org.apache.beam.sdk.coders.LengthPrefixCoder;
import org.apache.beam.sdk.coders.NullableCoder;
import org.apache.beam.sdk.coders.RowCoder;
import org.apache.beam.sdk.coders.TimestampPrefixingWindowCoder;
import org.apache.beam.sdk.schemas.Schema;
import org.apache.beam.sdk.schemas.SchemaTranslation;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.util.InstanceBuilder;
import org.apache.beam.sdk.util.ShardedKey;
import org.apache.beam.sdk.values.WindowedValues;
import org.apache.beam.sdk.values.WindowedValues.FullWindowedValueCoder;
import org.apache.beam.vendor.grpc.v1p69p0.com.google.protobuf.InvalidProtocolBufferException;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.collect.ImmutableList;

/** {@link CoderTranslator} implementations for known coder types. */
class CoderTranslators {
  private CoderTranslators() {}

  static <T extends Coder<?>> CoderTranslator<T> atomic(final Class<T> clazz) {
    return new SimpleStructuredCoderTranslator<T>() {
      @Override
      public List<? extends Coder<?>> getComponents(T from) {
        return Collections.emptyList();
      }

      @Override
      public T fromComponents(List<Coder<?>> components) {
        return InstanceBuilder.ofType(clazz).build();
      }
    };
  }

  static CoderTranslator<KvCoder<?, ?>> kv() {
    return new SimpleStructuredCoderTranslator<KvCoder<?, ?>>() {
      @Override
      public List<? extends Coder<?>> getComponents(KvCoder<?, ?> from) {
        return ImmutableList.of(from.getKeyCoder(), from.getValueCoder());
      }

      @Override
      public KvCoder<?, ?> fromComponents(List<Coder<?>> components) {
        return KvCoder.of(components.get(0), components.get(1));
      }
    };
  }

  static CoderTranslator<IterableCoder<?>> iterable() {
    return new SimpleStructuredCoderTranslator<IterableCoder<?>>() {
      @Override
      public List<? extends Coder<?>> getComponents(IterableCoder<?> from) {
        return Collections.singletonList(from.getElemCoder());
      }

      @Override
      public IterableCoder<?> fromComponents(List<Coder<?>> components) {
        return IterableCoder.of(components.get(0));
      }
    };
  }

  static CoderTranslator<Timer.Coder<?>> timer() {
    return new SimpleStructuredCoderTranslator<Timer.Coder<?>>() {
      @Override
      public List<? extends Coder<?>> getComponents(Timer.Coder<?> from) {
        return from.getComponents();
      }

      @Override
      public Timer.Coder<?> fromComponents(List<Coder<?>> components) {
        return Timer.Coder.of(components.get(0), (Coder<BoundedWindow>) components.get(1));
      }
    };
  }

  static CoderTranslator<LengthPrefixCoder<?>> lengthPrefix() {
    return new SimpleStructuredCoderTranslator<LengthPrefixCoder<?>>() {
      @Override
      public List<? extends Coder<?>> getComponents(LengthPrefixCoder<?> from) {
        return Collections.singletonList(from.getValueCoder());
      }

      @Override
      public LengthPrefixCoder<?> fromComponents(List<Coder<?>> components) {
        return LengthPrefixCoder.of(components.get(0));
      }
    };
  }

  static CoderTranslator<FullWindowedValueCoder<?>> fullWindowedValue() {
    return new SimpleStructuredCoderTranslator<FullWindowedValueCoder<?>>() {
      @Override
      public List<? extends Coder<?>> getComponents(FullWindowedValueCoder<?> from) {
        return ImmutableList.of(from.getValueCoder(), from.getWindowCoder());
      }

      @Override
      public FullWindowedValueCoder<?> fromComponents(List<Coder<?>> components) {
        return WindowedValues.getFullCoder(
            components.get(0), (Coder<BoundedWindow>) components.get(1));
      }
    };
  }

  static CoderTranslator<WindowedValues.ParamWindowedValueCoder<?>> paramWindowedValue() {
    return new CoderTranslator<WindowedValues.ParamWindowedValueCoder<?>>() {
      @Override
      public List<? extends Coder<?>> getComponents(
          WindowedValues.ParamWindowedValueCoder<?> from) {
        return ImmutableList.of(from.getValueCoder(), from.getWindowCoder());
      }

      @Override
      public byte[] getPayload(WindowedValues.ParamWindowedValueCoder<?> from) {
        return WindowedValues.ParamWindowedValueCoder.getPayload(from);
      }

      @Override
      public WindowedValues.ParamWindowedValueCoder<?> fromComponents(
          List<Coder<?>> components, byte[] payload, CoderTranslation.TranslationContext context) {
        return WindowedValues.ParamWindowedValueCoder.fromComponents(components, payload);
      }
    };
  }

  static CoderTranslator<RowCoder> row() {
    return new CoderTranslator<RowCoder>() {
      @Override
      public List<? extends Coder<?>> getComponents(RowCoder from) {
        return ImmutableList.of();
      }

      @Override
      public byte[] getPayload(RowCoder from) {
        return SchemaTranslation.schemaToProto(from.getSchema(), true).toByteArray();
      }

      @Override
      public RowCoder fromComponents(
          List<Coder<?>> components, byte[] payload, CoderTranslation.TranslationContext context) {
        checkArgument(
            components.isEmpty(), "Expected empty component list, but received: " + components);
        Schema schema;
        try {
          schema = SchemaTranslation.schemaFromProto(SchemaApi.Schema.parseFrom(payload));
        } catch (InvalidProtocolBufferException e) {
          throw new RuntimeException("Unable to parse schema for RowCoder: ", e);
        }
        return RowCoder.of(schema);
      }
    };
  }

  static CoderTranslator<ShardedKey.Coder<?>> shardedKey() {
    return new SimpleStructuredCoderTranslator<ShardedKey.Coder<?>>() {
      @Override
      public List<? extends Coder<?>> getComponents(ShardedKey.Coder<?> from) {
        return Collections.singletonList(from.getKeyCoder());
      }

      @Override
      public ShardedKey.Coder<?> fromComponents(List<Coder<?>> components) {
        return ShardedKey.Coder.of(components.get(0));
      }
    };
  }

  static CoderTranslator<TimestampPrefixingWindowCoder<?>> timestampPrefixingWindow() {
    return new SimpleStructuredCoderTranslator<TimestampPrefixingWindowCoder<?>>() {
      @Override
      protected TimestampPrefixingWindowCoder<?> fromComponents(List<Coder<?>> components) {
        return TimestampPrefixingWindowCoder.of((Coder<? extends BoundedWindow>) components.get(0));
      }

      @Override
      public List<? extends Coder<?>> getComponents(TimestampPrefixingWindowCoder<?> from) {
        return from.getComponents();
      }
    };
  }

  static CoderTranslator<NullableCoder<?>> nullable() {
    return new SimpleStructuredCoderTranslator<NullableCoder<?>>() {
      @Override
      protected NullableCoder<?> fromComponents(List<Coder<?>> components) {
        checkArgument(
            components.size() == 1, "Expected one component, but received: " + components);
        return NullableCoder.of(components.get(0));
      }

      @Override
      public List<? extends Coder<?>> getComponents(NullableCoder<?> from) {
        return from.getComponents();
      }
    };
  }

  public abstract static class SimpleStructuredCoderTranslator<T extends Coder<?>>
      implements CoderTranslator<T> {
    @Override
    public final T fromComponents(
        List<Coder<?>> components, byte[] payload, CoderTranslation.TranslationContext context) {
      return fromComponents(components);
    }

    protected abstract T fromComponents(List<Coder<?>> components);
  }
}
