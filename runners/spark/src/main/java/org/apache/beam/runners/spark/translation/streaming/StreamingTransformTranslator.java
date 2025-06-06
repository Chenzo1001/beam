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
package org.apache.beam.runners.spark.translation.streaming;

import static org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.base.Preconditions.checkArgument;
import static org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.base.Preconditions.checkState;

import com.google.auto.service.AutoService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.apache.beam.runners.core.construction.SerializablePipelineOptions;
import org.apache.beam.runners.spark.coders.CoderHelpers;
import org.apache.beam.runners.spark.io.ConsoleIO;
import org.apache.beam.runners.spark.io.CreateStream;
import org.apache.beam.runners.spark.io.SparkUnboundedSource;
import org.apache.beam.runners.spark.metrics.MetricsAccumulator;
import org.apache.beam.runners.spark.metrics.MetricsContainerStepMapAccumulator;
import org.apache.beam.runners.spark.stateful.SparkGroupAlsoByWindowViaWindowSet;
import org.apache.beam.runners.spark.translation.BoundedDataset;
import org.apache.beam.runners.spark.translation.Dataset;
import org.apache.beam.runners.spark.translation.EvaluationContext;
import org.apache.beam.runners.spark.translation.GroupCombineFunctions;
import org.apache.beam.runners.spark.translation.MultiDoFnFunction;
import org.apache.beam.runners.spark.translation.SingleEmitInputDStream;
import org.apache.beam.runners.spark.translation.SparkAssignWindowFn;
import org.apache.beam.runners.spark.translation.SparkCombineFn;
import org.apache.beam.runners.spark.translation.SparkPCollectionView;
import org.apache.beam.runners.spark.translation.SparkPipelineTranslator;
import org.apache.beam.runners.spark.translation.TransformEvaluator;
import org.apache.beam.runners.spark.translation.TranslationUtils;
import org.apache.beam.runners.spark.util.GlobalWatermarkHolder;
import org.apache.beam.runners.spark.util.SideInputBroadcast;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.coders.IterableCoder;
import org.apache.beam.sdk.coders.KvCoder;
import org.apache.beam.sdk.coders.ListCoder;
import org.apache.beam.sdk.testing.TestStream;
import org.apache.beam.sdk.transforms.Combine;
import org.apache.beam.sdk.transforms.CombineWithContext;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.DoFnSchemaInformation;
import org.apache.beam.sdk.transforms.Flatten;
import org.apache.beam.sdk.transforms.GroupByKey;
import org.apache.beam.sdk.transforms.Impulse;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.Reshuffle;
import org.apache.beam.sdk.transforms.reflect.DoFnSignature;
import org.apache.beam.sdk.transforms.reflect.DoFnSignatures;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.transforms.windowing.GlobalWindow;
import org.apache.beam.sdk.transforms.windowing.PaneInfo;
import org.apache.beam.sdk.transforms.windowing.Window;
import org.apache.beam.sdk.transforms.windowing.WindowFn;
import org.apache.beam.sdk.util.CombineFnUtil;
import org.apache.beam.sdk.util.construction.PTransformTranslation;
import org.apache.beam.sdk.util.construction.ParDoTranslation;
import org.apache.beam.sdk.util.construction.SplittableParDo;
import org.apache.beam.sdk.util.construction.TransformPayloadTranslatorRegistrar;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionView;
import org.apache.beam.sdk.values.PValue;
import org.apache.beam.sdk.values.TimestampedValue;
import org.apache.beam.sdk.values.TupleTag;
import org.apache.beam.sdk.values.WindowedValue;
import org.apache.beam.sdk.values.WindowedValues;
import org.apache.beam.sdk.values.WindowingStrategy;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.collect.ImmutableMap;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.collect.Iterables;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.collect.Lists;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.JavaSparkContext$;
import org.apache.spark.streaming.StreamingContext;
import org.apache.spark.streaming.api.java.JavaDStream;
import org.apache.spark.streaming.api.java.JavaInputDStream;
import org.apache.spark.streaming.api.java.JavaPairDStream;
import org.apache.spark.streaming.api.java.JavaStreamingContext;
import org.apache.spark.streaming.dstream.ConstantInputDStream;
import org.checkerframework.checker.nullness.qual.Nullable;
import scala.collection.JavaConverters;
import scala.reflect.ClassTag;
import scala.reflect.ClassTag$;

/** Supports translation between a Beam transform, and Spark's operations on DStreams. */
@SuppressWarnings({
  "rawtypes", // TODO(https://github.com/apache/beam/issues/20447)
  "nullness" // TODO(https://github.com/apache/beam/issues/20497)
})
public final class StreamingTransformTranslator {

  private StreamingTransformTranslator() {}

  private static <T> TransformEvaluator<ConsoleIO.Write.Unbound<T>> print() {
    return new TransformEvaluator<ConsoleIO.Write.Unbound<T>>() {
      @Override
      public void evaluate(ConsoleIO.Write.Unbound<T> transform, EvaluationContext context) {
        @SuppressWarnings("unchecked")
        JavaDStream<WindowedValue<T>> dstream =
            ((UnboundedDataset<T>) context.borrowDataset(transform)).getDStream();
        dstream.map(WindowedValue::getValue).print(transform.getNum());
      }

      @Override
      public String toNativeString() {
        return ".print(...)";
      }
    };
  }

  private static TransformEvaluator<Impulse> impulse() {
    return new TransformEvaluator<Impulse>() {
      @Override
      public void evaluate(Impulse transform, EvaluationContext context) {
        ClassTag<WindowedValue<byte[]>> classTag = ClassTag$.MODULE$.apply(WindowedValue.class);
        JavaRDD<WindowedValue<byte[]>> rdd =
            context
                .getSparkContext()
                .parallelize(
                    Collections.singletonList(WindowedValues.valueInGlobalWindow(new byte[0])));
        ConstantInputDStream<WindowedValue<byte[]>> inputStream =
            new ConstantInputDStream<>(context.getStreamingContext().ssc(), rdd.rdd(), classTag);
        JavaDStream<WindowedValue<byte[]>> stream = new JavaDStream<>(inputStream, classTag);
        UnboundedDataset<byte[]> output =
            new UnboundedDataset<>(stream, Collections.singletonList(inputStream.id()));
        context.putDataset(transform, output);
      }

      @Override
      public String toNativeString() {
        return "streamingContext.<impulse>()";
      }
    };
  }

  private static <T> TransformEvaluator<SplittableParDo.PrimitiveUnboundedRead<T>> readUnbounded() {
    return new TransformEvaluator<SplittableParDo.PrimitiveUnboundedRead<T>>() {
      @Override
      public void evaluate(
          SplittableParDo.PrimitiveUnboundedRead<T> transform, EvaluationContext context) {
        final String stepName = context.getCurrentTransform().getFullName();
        context.putDataset(
            transform,
            SparkUnboundedSource.read(
                context.getStreamingContext(),
                context.getSerializableOptions(),
                transform.getSource(),
                stepName));
      }

      @Override
      public String toNativeString() {
        return "streamingContext.<readFrom(<source>)>()";
      }
    };
  }

  private static <T> TransformEvaluator<TestStream<T>> createFromTestStream() {
    return new TransformEvaluator<TestStream<T>>() {

      @Override
      public void evaluate(TestStream<T> transform, EvaluationContext context) {
        TestDStream<T> dStream = new TestDStream<>(transform, context.getStreamingContext().ssc());
        JavaInputDStream<WindowedValue<T>> javaDStream =
            new JavaInputDStream<>(dStream, JavaSparkContext$.MODULE$.fakeClassTag());

        UnboundedDataset<T> dataset =
            new UnboundedDataset<>(javaDStream, Collections.singletonList(dStream.id()));
        context.putDataset(transform, dataset);
      }

      @Override
      public String toNativeString() {
        return "streamingContext.testStream(...)";
      }
    };
  }

  private static <T> TransformEvaluator<CreateStream<T>> createFromQueue() {
    return new TransformEvaluator<CreateStream<T>>() {
      @Override
      public void evaluate(CreateStream<T> transform, EvaluationContext context) {

        final Queue<JavaRDD<WindowedValue<T>>> rddQueue =
            buildRdds(
                transform.getBatches(),
                context.getStreamingContext(),
                context.getOutput(transform).getCoder());

        final JavaInputDStream<WindowedValue<T>> javaInputDStream =
            buildInputStream(rddQueue, transform, context);

        final UnboundedDataset<T> unboundedDataset =
            new UnboundedDataset<>(
                javaInputDStream, Collections.singletonList(javaInputDStream.inputDStream().id()));

        // add pre-baked Watermarks for the pre-baked batches.
        GlobalWatermarkHolder.addAll(
            ImmutableMap.of(unboundedDataset.getStreamSources().get(0), transform.getTimes()));

        context.putDataset(transform, unboundedDataset);
      }

      private Queue<JavaRDD<WindowedValue<T>>> buildRdds(
          Queue<Iterable<TimestampedValue<T>>> batches, JavaStreamingContext jssc, Coder<T> coder) {

        final WindowedValues.FullWindowedValueCoder<T> windowCoder =
            WindowedValues.FullWindowedValueCoder.of(coder, GlobalWindow.Coder.INSTANCE);

        final Queue<JavaRDD<WindowedValue<T>>> rddQueue = new LinkedBlockingQueue<>();

        for (final Iterable<TimestampedValue<T>> timestampedValues : batches) {
          final Iterable<WindowedValue<T>> windowedValues =
              StreamSupport.stream(timestampedValues.spliterator(), false)
                  .map(
                      timestampedValue ->
                          WindowedValues.of(
                              timestampedValue.getValue(),
                              timestampedValue.getTimestamp(),
                              GlobalWindow.INSTANCE,
                              PaneInfo.NO_FIRING))
                  .collect(Collectors.toList());

          final JavaRDD<WindowedValue<T>> rdd =
              jssc.sparkContext()
                  .parallelize(CoderHelpers.toByteArrays(windowedValues, windowCoder))
                  .map(CoderHelpers.fromByteFunction(windowCoder));

          rddQueue.offer(rdd);
        }
        return rddQueue;
      }

      private JavaInputDStream<WindowedValue<T>> buildInputStream(
          Queue<JavaRDD<WindowedValue<T>>> rddQueue,
          CreateStream<T> transform,
          EvaluationContext context) {
        return transform.isForceWatermarkSync()
            ? new JavaInputDStream<>(
                new WatermarkSyncedDStream<>(
                    rddQueue, transform.getBatchDuration(), context.getStreamingContext().ssc()),
                JavaSparkContext$.MODULE$.fakeClassTag())
            : context.getStreamingContext().queueStream(rddQueue, true);
      }

      @Override
      public String toNativeString() {
        return "streamingContext.queueStream(...)";
      }
    };
  }

  private static <T> TransformEvaluator<Flatten.PCollections<T>> flattenPColl() {
    return new TransformEvaluator<Flatten.PCollections<T>>() {
      @SuppressWarnings("unchecked")
      @Override
      public void evaluate(Flatten.PCollections<T> transform, EvaluationContext context) {
        Map<TupleTag<?>, PCollection<?>> pcs = context.getInputs(transform);
        // since this is a streaming pipeline, at least one of the PCollections to "flatten" are
        // unbounded, meaning it represents a DStream.
        // So we could end up with an unbounded unified DStream.
        final List<JavaDStream<WindowedValue<T>>> dStreams = new ArrayList<>();
        final List<Integer> streamingSources = new ArrayList<>();
        for (PValue pv : pcs.values()) {
          checkArgument(
              pv instanceof PCollection,
              "Flatten had non-PCollection value in input: %s of type %s",
              pv,
              pv.getClass().getSimpleName());
          PCollection<T> pcol = (PCollection<T>) pv;
          Dataset dataset = context.borrowDataset(pcol);
          if (dataset instanceof UnboundedDataset) {
            UnboundedDataset<T> unboundedDataset = (UnboundedDataset<T>) dataset;
            streamingSources.addAll(unboundedDataset.getStreamSources());
            dStreams.add(unboundedDataset.getDStream());
          } else {
            // create a single RDD stream.
            dStreams.add(
                this.buildDStream(context.getStreamingContext().ssc(), (BoundedDataset) dataset));
          }
        }
        // start by unifying streams into a single stream.
        JavaDStream<WindowedValue<T>> unifiedStreams =
            context.getStreamingContext().union(JavaConverters.asScalaBuffer(dStreams));
        context.putDataset(transform, new UnboundedDataset<>(unifiedStreams, streamingSources));
      }

      private JavaDStream<WindowedValue<T>> buildDStream(
          final StreamingContext ssc, final BoundedDataset<T> dataset) {

        final SingleEmitInputDStream<WindowedValue<T>> singleEmitDStream =
            new SingleEmitInputDStream<>(ssc, dataset.getRDD().rdd());

        return JavaDStream.fromDStream(singleEmitDStream, JavaSparkContext$.MODULE$.fakeClassTag());
      }

      @Override
      public String toNativeString() {
        return "streamingContext.union(...)";
      }
    };
  }

  private static <T, W extends BoundedWindow> TransformEvaluator<Window.Assign<T>> window() {
    return new TransformEvaluator<Window.Assign<T>>() {
      @Override
      public void evaluate(final Window.Assign<T> transform, EvaluationContext context) {
        @SuppressWarnings("unchecked")
        UnboundedDataset<T> unboundedDataset =
            (UnboundedDataset<T>) context.borrowDataset(transform);
        JavaDStream<WindowedValue<T>> dStream = unboundedDataset.getDStream();
        JavaDStream<WindowedValue<T>> outputStream;
        if (TranslationUtils.skipAssignWindows(transform, context)) {
          // do nothing.
          outputStream = dStream;
        } else {
          outputStream =
              dStream.transform(rdd -> rdd.map(new SparkAssignWindowFn<>(transform.getWindowFn())));
        }
        context.putDataset(
            transform, new UnboundedDataset<>(outputStream, unboundedDataset.getStreamSources()));
      }

      @Override
      public String toNativeString() {
        return "map(new <windowFn>())";
      }
    };
  }

  private static <K, V, W extends BoundedWindow> TransformEvaluator<GroupByKey<K, V>> groupByKey() {
    return new TransformEvaluator<GroupByKey<K, V>>() {
      @Override
      public void evaluate(GroupByKey<K, V> transform, EvaluationContext context) {
        @SuppressWarnings("unchecked")
        UnboundedDataset<KV<K, V>> inputDataset =
            (UnboundedDataset<KV<K, V>>) context.borrowDataset(transform);
        List<Integer> streamSources = inputDataset.getStreamSources();
        JavaDStream<WindowedValue<KV<K, V>>> dStream = inputDataset.getDStream();
        final KvCoder<K, V> coder = (KvCoder<K, V>) context.getInput(transform).getCoder();
        @SuppressWarnings("unchecked")
        final WindowingStrategy<?, W> windowingStrategy =
            (WindowingStrategy<?, W>) context.getInput(transform).getWindowingStrategy();
        @SuppressWarnings("unchecked")
        final WindowFn<Object, W> windowFn = (WindowFn<Object, W>) windowingStrategy.getWindowFn();

        // --- coders.
        final WindowedValues.WindowedValueCoder<V> wvCoder =
            WindowedValues.FullWindowedValueCoder.of(coder.getValueCoder(), windowFn.windowCoder());

        JavaDStream<WindowedValue<KV<K, Iterable<V>>>> outStream =
            SparkGroupAlsoByWindowViaWindowSet.groupByKeyAndWindow(
                dStream,
                coder.getKeyCoder(),
                wvCoder,
                windowingStrategy,
                context.getSerializableOptions(),
                streamSources,
                context.getCurrentTransform().getFullName());

        context.putDataset(transform, new UnboundedDataset<>(outStream, streamSources));
      }

      @Override
      public String toNativeString() {
        return "groupByKey()";
      }
    };
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static <ElemT, ViewT>
      TransformEvaluator<CreateStreamingSparkView.CreateSparkPCollectionView<ElemT, ViewT>>
          streamingSideInput() {
    return new TransformEvaluator<
        CreateStreamingSparkView.CreateSparkPCollectionView<ElemT, ViewT>>() {
      @Override
      public void evaluate(
          CreateStreamingSparkView.CreateSparkPCollectionView<ElemT, ViewT> transform,
          EvaluationContext context) {
        final PCollection<List<ElemT>> input = context.getInput(transform);
        final UnboundedDataset<ElemT> dataset =
            (UnboundedDataset<ElemT>) context.borrowDataset(input);
        final PCollectionView<ViewT> output = transform.getView();

        final JavaDStream<WindowedValue<ElemT>> dStream = dataset.getDStream();

        Coder<WindowedValue<?>> coderInternal =
            (Coder)
                WindowedValues.getFullCoder(
                    ListCoder.of(output.getCoderInternal()),
                    output.getWindowingStrategyInternal().getWindowFn().windowCoder());

        // Convert JavaDStream to byte array
        // The (JavaDStream) cast is used to prevent CheckerFramework type checking errors
        // CheckerFramework treats mismatched generic type parameters as errors,
        // but at runtime this is safe due to type erasure
        final JavaDStream<byte[]> byteConverted =
            (JavaDStream)
                dStream.mapPartitions(
                    (Iterator<WindowedValue<ElemT>> iter) ->
                        CoderHelpers.toByteArrays(iter, (Coder) coderInternal).iterator());

        // Update side input values whenever a new RDD arrives
        final SparkPCollectionView pViews = context.getPViews();
        byteConverted.foreachRDD(
            (JavaRDD<byte[]> rdd) -> {
              final List<byte[]> collect = rdd.collect();
              final Iterable<WindowedValue<ElemT>> iterable =
                  CoderHelpers.fromByteArrays(collect, (Coder) coderInternal);

              if (!Iterables.isEmpty(iterable)) {
                pViews.putStreamingPView(
                    output, (Iterable) iterable, IterableCoder.of(coderInternal));
              }
            });

        // Enable streaming side input mode
        context.useStreamingSideInput();

        // Initialize with empty side input values
        // In streaming environment, data from DStream is not immediately available
        // The system initializes with empty values and updates them when data arrives
        // This means side inputs may initially be null
        context.putPView(
            output, /*Empty Side Inputs*/ Lists.newArrayList(), IterableCoder.of(coderInternal));
      }

      @Override
      public String toNativeString() {
        return "streamingView()";
      }
    };
  }

  private static <K, InputT, OutputT>
      TransformEvaluator<Combine.GroupedValues<K, InputT, OutputT>> combineGrouped() {
    return new TransformEvaluator<Combine.GroupedValues<K, InputT, OutputT>>() {
      @Override
      public void evaluate(
          final Combine.GroupedValues<K, InputT, OutputT> transform, EvaluationContext context) {
        // get the applied combine function.
        PCollection<? extends KV<K, ? extends Iterable<InputT>>> input =
            context.getInput(transform);
        final WindowingStrategy<?, ?> windowingStrategy = input.getWindowingStrategy();
        @SuppressWarnings("unchecked")
        final CombineWithContext.CombineFnWithContext<InputT, ?, OutputT> fn =
            (CombineWithContext.CombineFnWithContext<InputT, ?, OutputT>)
                CombineFnUtil.toFnWithContext(transform.getFn());

        @SuppressWarnings("unchecked")
        UnboundedDataset<KV<K, Iterable<InputT>>> unboundedDataset =
            (UnboundedDataset<KV<K, Iterable<InputT>>>) context.borrowDataset(transform);
        JavaDStream<WindowedValue<KV<K, Iterable<InputT>>>> dStream = unboundedDataset.getDStream();

        final SerializablePipelineOptions options = context.getSerializableOptions();
        final SparkPCollectionView pviews = context.getPViews();

        JavaDStream<WindowedValue<KV<K, OutputT>>> outStream =
            dStream.transform(
                rdd -> {
                  SparkCombineFn<KV<K, InputT>, InputT, ?, OutputT> combineFnWithContext =
                      SparkCombineFn.keyed(
                          fn,
                          options,
                          TranslationUtils.getSideInputs(
                              transform.getSideInputs(),
                              new JavaSparkContext(rdd.context()),
                              pviews),
                          windowingStrategy);
                  return rdd.map(new TranslationUtils.CombineGroupedValues<>(combineFnWithContext));
                });

        context.putDataset(
            transform, new UnboundedDataset<>(outStream, unboundedDataset.getStreamSources()));
      }

      @Override
      public String toNativeString() {
        return "map(new <fn>())";
      }
    };
  }

  private static <InputT, OutputT> TransformEvaluator<ParDo.MultiOutput<InputT, OutputT>> parDo() {
    return new TransformEvaluator<ParDo.MultiOutput<InputT, OutputT>>() {
      @Override
      public void evaluate(
          final ParDo.MultiOutput<InputT, OutputT> transform, final EvaluationContext context) {
        final DoFn<InputT, OutputT> doFn = transform.getFn();
        final DoFnSignature signature = DoFnSignatures.signatureForDoFn(doFn);
        checkArgument(
            !signature.processElement().isSplittable(),
            "Splittable DoFn not yet supported in streaming mode: %s",
            doFn);
        checkState(
            signature.onWindowExpiration() == null,
            "onWindowExpiration is not supported: %s",
            doFn);

        boolean stateful =
            signature.stateDeclarations().size() > 0 || signature.timerDeclarations().size() > 0;

        if (stateful) {
          final StatefulStreamingParDoEvaluator<?, ?, ?> delegate =
              new StatefulStreamingParDoEvaluator<>();

          delegate.evaluate((ParDo.MultiOutput) transform, context);
          return;
        }

        final SerializablePipelineOptions options = context.getSerializableOptions();
        final SparkPCollectionView pviews = context.getPViews();
        final WindowingStrategy<?, ?> windowingStrategy =
            context.getInput(transform).getWindowingStrategy();
        Coder<InputT> inputCoder = (Coder<InputT>) context.getInput(transform).getCoder();
        Map<TupleTag<?>, Coder<?>> outputCoders = context.getOutputCoders();

        @SuppressWarnings("unchecked")
        UnboundedDataset<InputT> unboundedDataset =
            (UnboundedDataset<InputT>) context.borrowDataset(transform);
        JavaDStream<WindowedValue<InputT>> dStream = unboundedDataset.getDStream();

        final DoFnSchemaInformation doFnSchemaInformation =
            ParDoTranslation.getSchemaInformation(context.getCurrentTransform());

        final Map<String, PCollectionView<?>> sideInputMapping =
            ParDoTranslation.getSideInputMapping(context.getCurrentTransform());

        final boolean useStreamingSideInput = context.isStreamingSideInput();

        final String stepName = context.getCurrentTransform().getFullName();
        JavaPairDStream<TupleTag<?>, WindowedValue<?>> all =
            dStream.transformToPair(
                rdd -> {
                  final MetricsContainerStepMapAccumulator metricsAccum =
                      MetricsAccumulator.getInstance();
                  final Map<TupleTag<?>, KV<WindowingStrategy<?, ?>, SideInputBroadcast<?>>>
                      sideInputs =
                          TranslationUtils.getSideInputs(
                              transform.getSideInputs().values(),
                              JavaSparkContext.fromSparkContext(rdd.context()),
                              pviews);

                  return rdd.mapPartitionsToPair(
                      new MultiDoFnFunction<>(
                          metricsAccum,
                          stepName,
                          doFn,
                          options,
                          transform.getMainOutputTag(),
                          transform.getAdditionalOutputTags().getAll(),
                          inputCoder,
                          outputCoders,
                          sideInputs,
                          windowingStrategy,
                          false,
                          doFnSchemaInformation,
                          sideInputMapping,
                          false,
                          useStreamingSideInput));
                });

        Map<TupleTag<?>, PCollection<?>> outputs = context.getOutputs(transform);
        if (outputs.size() > 1) {
          // Caching can cause Serialization, we need to code to bytes
          // more details in https://issues.apache.org/jira/browse/BEAM-2669
          Map<TupleTag<?>, Coder<WindowedValue<?>>> coderMap =
              TranslationUtils.getTupleTagCoders(outputs);
          all =
              all.mapToPair(TranslationUtils.getTupleTagEncodeFunction(coderMap))
                  .cache()
                  .mapToPair(TranslationUtils.getTupleTagDecodeFunction(coderMap));
        }

        for (Map.Entry<TupleTag<?>, PCollection<?>> output : outputs.entrySet()) {
          @SuppressWarnings("unchecked")
          JavaPairDStream<TupleTag<?>, WindowedValue<?>> filtered =
              all.filter(new TranslationUtils.TupleTagFilter(output.getKey()));
          @SuppressWarnings("unchecked")
          // Object is the best we can do since different outputs can have different tags
          JavaDStream<WindowedValue<Object>> values =
              (JavaDStream<WindowedValue<Object>>)
                  (JavaDStream<?>) TranslationUtils.dStreamValues(filtered);
          context.putDataset(
              output.getValue(),
              new UnboundedDataset<>(values, unboundedDataset.getStreamSources()));
        }
      }

      @Override
      public String toNativeString() {
        return "mapPartitions(new <fn>())";
      }
    };
  }

  private static <K, V, W extends BoundedWindow> TransformEvaluator<Reshuffle<K, V>> reshuffle() {
    return new TransformEvaluator<Reshuffle<K, V>>() {
      @Override
      public void evaluate(Reshuffle<K, V> transform, EvaluationContext context) {
        @SuppressWarnings("unchecked")
        UnboundedDataset<KV<K, V>> inputDataset =
            (UnboundedDataset<KV<K, V>>) context.borrowDataset(transform);
        List<Integer> streamSources = inputDataset.getStreamSources();
        JavaDStream<WindowedValue<KV<K, V>>> dStream = inputDataset.getDStream();
        final KvCoder<K, V> coder = (KvCoder<K, V>) context.getInput(transform).getCoder();
        @SuppressWarnings("unchecked")
        final WindowingStrategy<?, W> windowingStrategy =
            (WindowingStrategy<?, W>) context.getInput(transform).getWindowingStrategy();
        @SuppressWarnings("unchecked")
        final WindowFn<Object, W> windowFn = (WindowFn<Object, W>) windowingStrategy.getWindowFn();

        final WindowedValues.WindowedValueCoder<KV<K, V>> wvCoder =
            WindowedValues.FullWindowedValueCoder.of(coder, windowFn.windowCoder());

        JavaDStream<WindowedValue<KV<K, V>>> reshuffledStream =
            dStream.transform(rdd -> GroupCombineFunctions.reshuffle(rdd, wvCoder));

        context.putDataset(transform, new UnboundedDataset<>(reshuffledStream, streamSources));
      }

      @Override
      public String toNativeString() {
        return "repartition(...)";
      }
    };
  }

  private static final Map<String, TransformEvaluator<?>> EVALUATORS = new HashMap<>();

  static {
    EVALUATORS.put(PTransformTranslation.IMPULSE_TRANSFORM_URN, impulse());
    EVALUATORS.put(PTransformTranslation.READ_TRANSFORM_URN, readUnbounded());
    EVALUATORS.put(PTransformTranslation.GROUP_BY_KEY_TRANSFORM_URN, groupByKey());
    EVALUATORS.put(PTransformTranslation.COMBINE_GROUPED_VALUES_TRANSFORM_URN, combineGrouped());
    EVALUATORS.put(PTransformTranslation.PAR_DO_TRANSFORM_URN, parDo());
    EVALUATORS.put(ConsoleIO.Write.Unbound.TRANSFORM_URN, print());
    EVALUATORS.put(PTransformTranslation.ASSIGN_WINDOWS_TRANSFORM_URN, window());
    EVALUATORS.put(PTransformTranslation.FLATTEN_TRANSFORM_URN, flattenPColl());
    EVALUATORS.put(PTransformTranslation.RESHUFFLE_URN, reshuffle());
    EVALUATORS.put(CreateStreamingSparkView.CREATE_STREAMING_SPARK_VIEW_URN, streamingSideInput());
    // For testing only
    EVALUATORS.put(CreateStream.TRANSFORM_URN, createFromQueue());
    EVALUATORS.put(PTransformTranslation.TEST_STREAM_TRANSFORM_URN, createFromTestStream());
  }

  private static @Nullable TransformEvaluator<?> getTranslator(PTransform<?, ?> transform) {
    @Nullable String urn = PTransformTranslation.urnForTransformOrNull(transform);
    if (transform instanceof CreateStreamingSparkView.CreateSparkPCollectionView) {
      urn = CreateStreamingSparkView.CREATE_STREAMING_SPARK_VIEW_URN;
    }
    return urn == null ? null : EVALUATORS.get(urn);
  }

  /** Translator matches Beam transformation with the appropriate evaluator. */
  public static class Translator implements SparkPipelineTranslator {

    private final SparkPipelineTranslator batchTranslator;

    public Translator(SparkPipelineTranslator batchTranslator) {
      this.batchTranslator = batchTranslator;
    }

    @Override
    public boolean hasTranslation(PTransform<?, ?> transform) {
      // streaming includes rdd/bounded transformations as well
      return EVALUATORS.containsKey(PTransformTranslation.urnForTransformOrNull(transform));
    }

    @Override
    public <TransformT extends PTransform<?, ?>> TransformEvaluator<TransformT> translateBounded(
        PTransform<?, ?> transform) {
      TransformEvaluator<TransformT> transformEvaluator =
          batchTranslator.translateBounded(transform);
      checkState(
          transformEvaluator != null,
          "No TransformEvaluator registered for BOUNDED transform %s",
          transform);
      return transformEvaluator;
    }

    @Override
    public <TransformT extends PTransform<?, ?>> TransformEvaluator<TransformT> translateUnbounded(
        PTransform<?, ?> transform) {
      @SuppressWarnings("unchecked")
      TransformEvaluator<TransformT> transformEvaluator =
          (TransformEvaluator<TransformT>) getTranslator(transform);
      checkState(
          transformEvaluator != null,
          "No TransformEvaluator registered for UNBOUNDED transform %s",
          transform);
      return transformEvaluator;
    }
  }

  /** Registers classes specialized by the Spark runner. */
  @AutoService(TransformPayloadTranslatorRegistrar.class)
  public static class SparkTransformsRegistrar implements TransformPayloadTranslatorRegistrar {
    @Override
    public Map<
            ? extends Class<? extends PTransform>,
            ? extends PTransformTranslation.TransformPayloadTranslator>
        getTransformPayloadTranslators() {
      return ImmutableMap.of(
          ConsoleIO.Write.Unbound.class, new SparkConsoleIOWriteUnboundedPayloadTranslator(),
          CreateStream.class, new SparkCreateStreamPayloadTranslator());
    }
  }

  private static class SparkConsoleIOWriteUnboundedPayloadTranslator
      extends PTransformTranslation.TransformPayloadTranslator.NotSerializable<
          ConsoleIO.Write.Unbound<?>> {

    @Override
    public String getUrn() {
      return ConsoleIO.Write.Unbound.TRANSFORM_URN;
    }
  }

  private static class SparkCreateStreamPayloadTranslator
      extends PTransformTranslation.TransformPayloadTranslator.NotSerializable<CreateStream<?>> {

    @Override
    public String getUrn() {
      return CreateStream.TRANSFORM_URN;
    }
  }
}
