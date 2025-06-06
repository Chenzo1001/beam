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
package org.apache.beam.runners.spark.translation;

import static org.apache.beam.runners.fnexecution.translation.PipelineTranslatorUtils.createOutputMap;
import static org.apache.beam.runners.fnexecution.translation.PipelineTranslatorUtils.getExecutableStageIntermediateId;
import static org.apache.beam.runners.fnexecution.translation.PipelineTranslatorUtils.getInputId;
import static org.apache.beam.runners.fnexecution.translation.PipelineTranslatorUtils.getOutputId;
import static org.apache.beam.runners.fnexecution.translation.PipelineTranslatorUtils.getWindowedValueCoder;
import static org.apache.beam.runners.fnexecution.translation.PipelineTranslatorUtils.getWindowingStrategy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.beam.model.pipeline.v1.RunnerApi;
import org.apache.beam.runners.fnexecution.provisioning.JobInfo;
import org.apache.beam.runners.spark.SparkPipelineOptions;
import org.apache.beam.runners.spark.coders.CoderHelpers;
import org.apache.beam.runners.spark.metrics.MetricsAccumulator;
import org.apache.beam.runners.spark.stateful.SparkGroupAlsoByWindowViaWindowSet;
import org.apache.beam.runners.spark.translation.streaming.UnboundedDataset;
import org.apache.beam.runners.spark.util.GlobalWatermarkHolder;
import org.apache.beam.sdk.coders.ByteArrayCoder;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.coders.KvCoder;
import org.apache.beam.sdk.transforms.join.RawUnionValue;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.transforms.windowing.GlobalWindow;
import org.apache.beam.sdk.transforms.windowing.PaneInfo;
import org.apache.beam.sdk.transforms.windowing.WindowFn;
import org.apache.beam.sdk.util.construction.PTransformTranslation;
import org.apache.beam.sdk.util.construction.graph.ExecutableStage;
import org.apache.beam.sdk.util.construction.graph.PipelineNode;
import org.apache.beam.sdk.util.construction.graph.PipelineNode.PTransformNode;
import org.apache.beam.sdk.util.construction.graph.QueryablePipeline;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.WindowedValue;
import org.apache.beam.sdk.values.WindowedValues;
import org.apache.beam.sdk.values.WindowingStrategy;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.collect.BiMap;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.collect.ImmutableMap;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.JavaSparkContext$;
import org.apache.spark.broadcast.Broadcast;
import org.apache.spark.storage.StorageLevel;
import org.apache.spark.streaming.api.java.JavaDStream;
import scala.Tuple2;
import scala.collection.JavaConverters;

/** Translates an unbounded portable pipeline into a Spark job. */
@SuppressWarnings({
  "rawtypes", // TODO(https://github.com/apache/beam/issues/20447)
  "nullness" // TODO(https://github.com/apache/beam/issues/20497)
})
public class SparkStreamingPortablePipelineTranslator
    implements SparkPortablePipelineTranslator<SparkStreamingTranslationContext> {

  private final ImmutableMap<String, PTransformTranslator> urnToTransformTranslator;

  interface PTransformTranslator {

    /** Translates transformNode from Beam into the Spark context. */
    void translate(
        PTransformNode transformNode,
        RunnerApi.Pipeline pipeline,
        SparkStreamingTranslationContext context);
  }

  @Override
  public Set<String> knownUrns() {
    return urnToTransformTranslator.keySet();
  }

  public SparkStreamingPortablePipelineTranslator() {
    ImmutableMap.Builder<String, PTransformTranslator> translatorMap = ImmutableMap.builder();
    translatorMap.put(
        PTransformTranslation.IMPULSE_TRANSFORM_URN,
        SparkStreamingPortablePipelineTranslator::translateImpulse);
    translatorMap.put(
        PTransformTranslation.GROUP_BY_KEY_TRANSFORM_URN,
        SparkStreamingPortablePipelineTranslator::translateGroupByKey);
    translatorMap.put(
        ExecutableStage.URN, SparkStreamingPortablePipelineTranslator::translateExecutableStage);
    translatorMap.put(
        PTransformTranslation.FLATTEN_TRANSFORM_URN,
        SparkStreamingPortablePipelineTranslator::translateFlatten);
    translatorMap.put(
        PTransformTranslation.RESHUFFLE_URN,
        SparkStreamingPortablePipelineTranslator::translateReshuffle);
    this.urnToTransformTranslator = translatorMap.build();
  }

  /** Translates pipeline from Beam into the Spark context. */
  @Override
  public void translate(
      final RunnerApi.Pipeline pipeline, SparkStreamingTranslationContext context) {
    QueryablePipeline p =
        QueryablePipeline.forTransforms(
            pipeline.getRootTransformIdsList(), pipeline.getComponents());
    for (PipelineNode.PTransformNode transformNode : p.getTopologicallyOrderedTransforms()) {
      urnToTransformTranslator
          .getOrDefault(
              transformNode.getTransform().getSpec().getUrn(),
              SparkStreamingPortablePipelineTranslator::urnNotFound)
          .translate(transformNode, pipeline, context);
    }
  }

  private static void urnNotFound(
      PTransformNode transformNode,
      RunnerApi.Pipeline pipeline,
      SparkStreamingTranslationContext context) {
    throw new IllegalArgumentException(
        String.format(
            "Transform %s has unknown URN %s",
            transformNode.getId(), transformNode.getTransform().getSpec().getUrn()));
  }

  private static void translateImpulse(
      PTransformNode transformNode,
      RunnerApi.Pipeline pipeline,
      SparkStreamingTranslationContext context) {

    Iterable<WindowedValue<byte[]>> windowedValues =
        Collections.singletonList(
            WindowedValues.of(
                new byte[0],
                BoundedWindow.TIMESTAMP_MIN_VALUE,
                GlobalWindow.INSTANCE,
                PaneInfo.NO_FIRING));

    WindowedValues.FullWindowedValueCoder<byte[]> windowCoder =
        WindowedValues.FullWindowedValueCoder.of(ByteArrayCoder.of(), GlobalWindow.Coder.INSTANCE);
    JavaRDD<WindowedValue<byte[]>> emptyByteArrayRDD =
        context
            .getSparkContext()
            .parallelize(CoderHelpers.toByteArrays(windowedValues, windowCoder))
            .map(CoderHelpers.fromByteFunction(windowCoder));

    final SingleEmitInputDStream<WindowedValue<byte[]>> inputDStream =
        new SingleEmitInputDStream<>(context.getStreamingContext().ssc(), emptyByteArrayRDD.rdd());

    final JavaDStream<WindowedValue<byte[]>> stream =
        JavaDStream.fromDStream(inputDStream, JavaSparkContext$.MODULE$.fakeClassTag());

    UnboundedDataset<byte[]> output =
        new UnboundedDataset<>(stream, Collections.singletonList(inputDStream.id()));

    // Add watermark to holder and advance to infinity to ensure future watermarks can be updated
    GlobalWatermarkHolder.SparkWatermarks sparkWatermark =
        new GlobalWatermarkHolder.SparkWatermarks(
            GlobalWindow.INSTANCE.maxTimestamp(),
            BoundedWindow.TIMESTAMP_MAX_VALUE,
            context.getFirstTimestamp());
    GlobalWatermarkHolder.add(output.getStreamSources().get(0), sparkWatermark);
    context.pushDataset(getOutputId(transformNode), output);
  }

  private static <K, V> void translateGroupByKey(
      PTransformNode transformNode,
      RunnerApi.Pipeline pipeline,
      SparkStreamingTranslationContext context) {
    RunnerApi.Components components = pipeline.getComponents();
    String inputId = getInputId(transformNode);
    UnboundedDataset<KV<K, V>> inputDataset =
        (UnboundedDataset<KV<K, V>>) context.popDataset(inputId);
    List<Integer> streamSources = inputDataset.getStreamSources();
    WindowedValues.WindowedValueCoder<KV<K, V>> inputCoder =
        getWindowedValueCoder(inputId, components);
    KvCoder<K, V> inputKvCoder = (KvCoder<K, V>) inputCoder.getValueCoder();
    WindowingStrategy windowingStrategy = getWindowingStrategy(inputId, components);
    WindowFn<Object, BoundedWindow> windowFn = windowingStrategy.getWindowFn();
    WindowedValues.WindowedValueCoder<V> wvCoder =
        WindowedValues.FullWindowedValueCoder.of(
            inputKvCoder.getValueCoder(), windowFn.windowCoder());

    JavaDStream<WindowedValue<KV<K, Iterable<V>>>> outStream =
        SparkGroupAlsoByWindowViaWindowSet.groupByKeyAndWindow(
            inputDataset.getDStream(),
            inputKvCoder.getKeyCoder(),
            wvCoder,
            windowingStrategy,
            context.getSerializableOptions(),
            streamSources,
            transformNode.getId());

    context.pushDataset(
        getOutputId(transformNode), new UnboundedDataset<>(outStream, streamSources));
  }

  private static <InputT, OutputT, SideInputT> void translateExecutableStage(
      PTransformNode transformNode,
      RunnerApi.Pipeline pipeline,
      SparkStreamingTranslationContext context) {
    RunnerApi.ExecutableStagePayload stagePayload;
    try {
      stagePayload =
          RunnerApi.ExecutableStagePayload.parseFrom(
              transformNode.getTransform().getSpec().getPayload());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    String inputPCollectionId = stagePayload.getInput();
    UnboundedDataset<InputT> inputDataset =
        (UnboundedDataset<InputT>) context.popDataset(inputPCollectionId);
    List<Integer> streamSources = inputDataset.getStreamSources();
    JavaDStream<WindowedValue<InputT>> inputDStream = inputDataset.getDStream();
    Map<String, String> outputs = transformNode.getTransform().getOutputsMap();
    BiMap<String, Integer> outputMap = createOutputMap(outputs.values());

    RunnerApi.Components components = pipeline.getComponents();
    Coder windowCoder =
        getWindowingStrategy(inputPCollectionId, components).getWindowFn().windowCoder();

    // TODO (https://github.com/apache/beam/issues/20395): handle side inputs.
    if (stagePayload.getSideInputsCount() > 0) {
      throw new UnsupportedOperationException(
          "Side inputs to executable stage are currently unsupported.");
    }
    ImmutableMap<
            String, Tuple2<Broadcast<List<byte[]>>, WindowedValues.WindowedValueCoder<SideInputT>>>
        broadcastVariables = ImmutableMap.copyOf(new HashMap<>());

    SparkExecutableStageFunction<InputT, SideInputT> function =
        new SparkExecutableStageFunction<>(
            context.getSerializableOptions(),
            stagePayload,
            context.jobInfo,
            outputMap,
            SparkExecutableStageContextFactory.getInstance(),
            broadcastVariables,
            MetricsAccumulator.getInstance(),
            windowCoder);
    JavaDStream<RawUnionValue> staged = inputDStream.mapPartitions(function);

    String intermediateId = getExecutableStageIntermediateId(transformNode);
    context.pushDataset(
        intermediateId,
        new Dataset() {
          @Override
          public void cache(String storageLevel, Coder<?> coder) {
            StorageLevel level = StorageLevel.fromString(storageLevel);
            staged.persist(level);
          }

          @Override
          public void action() {
            // Empty function to force computation of RDD.
            staged.foreachRDD(TranslationUtils.emptyVoidFunction());
          }

          @Override
          public void setName(String name) {
            // ignore
          }
        });
    // Pop dataset to mark DStream as used
    context.popDataset(intermediateId);

    for (String outputId : outputs.values()) {
      JavaDStream<WindowedValue<OutputT>> outStream =
          staged.flatMap(new SparkExecutableStageExtractionFunction<>(outputMap.get(outputId)));
      context.pushDataset(outputId, new UnboundedDataset<>(outStream, streamSources));
    }

    // Add sink to ensure stage is executed
    if (outputs.isEmpty()) {
      JavaDStream<WindowedValue<OutputT>> outStream =
          staged.flatMap((rawUnionValue) -> Collections.emptyIterator());
      context.pushDataset(
          String.format("EmptyOutputSink_%d", context.nextSinkId()),
          new UnboundedDataset<>(outStream, streamSources));
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> void translateFlatten(
      PTransformNode transformNode,
      RunnerApi.Pipeline pipeline,
      SparkStreamingTranslationContext context) {
    Map<String, String> inputsMap = transformNode.getTransform().getInputsMap();
    JavaDStream<WindowedValue<T>> unifiedStreams;
    List<Integer> streamSources = new ArrayList<>();

    if (inputsMap.isEmpty()) {
      final JavaRDD<WindowedValue<T>> emptyRDD = context.getSparkContext().emptyRDD();
      final SingleEmitInputDStream<WindowedValue<T>> singleEmitInputDStream =
          new SingleEmitInputDStream<>(context.getStreamingContext().ssc(), emptyRDD.rdd());
      unifiedStreams =
          JavaDStream.fromDStream(singleEmitInputDStream, JavaSparkContext$.MODULE$.fakeClassTag());
    } else {
      List<JavaDStream<WindowedValue<T>>> dStreams = new ArrayList<>();
      for (String inputId : inputsMap.values()) {
        Dataset dataset = context.popDataset(inputId);
        if (dataset instanceof UnboundedDataset) {
          UnboundedDataset<T> unboundedDataset = (UnboundedDataset<T>) dataset;
          streamSources.addAll(unboundedDataset.getStreamSources());
          dStreams.add(unboundedDataset.getDStream());
        } else {
          // create a single RDD stream.
          final SingleEmitInputDStream<WindowedValue<T>> singleEmitInputDStream =
              new SingleEmitInputDStream<WindowedValue<T>>(
                  context.getStreamingContext().ssc(), ((BoundedDataset) dataset).getRDD().rdd());
          final JavaDStream<WindowedValue<T>> dStream =
              JavaDStream.fromDStream(
                  singleEmitInputDStream, JavaSparkContext$.MODULE$.fakeClassTag());

          dStreams.add(dStream);
        }
      }
      // Unify streams into a single stream.
      unifiedStreams = context.getStreamingContext().union(JavaConverters.asScalaBuffer(dStreams));
    }

    context.pushDataset(
        getOutputId(transformNode), new UnboundedDataset<>(unifiedStreams, streamSources));
  }

  private static <T> void translateReshuffle(
      PTransformNode transformNode,
      RunnerApi.Pipeline pipeline,
      SparkStreamingTranslationContext context) {
    String inputId = getInputId(transformNode);
    UnboundedDataset<T> inputDataset = (UnboundedDataset<T>) context.popDataset(inputId);
    List<Integer> streamSources = inputDataset.getStreamSources();
    JavaDStream<WindowedValue<T>> dStream = inputDataset.getDStream();
    WindowedValues.WindowedValueCoder<T> coder =
        getWindowedValueCoder(inputId, pipeline.getComponents());

    JavaDStream<WindowedValue<T>> reshuffledStream =
        dStream.transform(rdd -> GroupCombineFunctions.reshuffle(rdd, coder));

    context.pushDataset(
        getOutputId(transformNode), new UnboundedDataset<>(reshuffledStream, streamSources));
  }

  @Override
  public SparkStreamingTranslationContext createTranslationContext(
      JavaSparkContext jsc, SparkPipelineOptions options, JobInfo jobInfo) {
    return new SparkStreamingTranslationContext(jsc, options, jobInfo);
  }
}
