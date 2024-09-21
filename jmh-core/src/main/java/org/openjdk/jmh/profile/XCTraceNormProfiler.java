/*
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.openjdk.jmh.profile;

import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.results.*;
import org.openjdk.jmh.util.FileUtils;
import org.openjdk.jmh.util.TempFile;

import java.io.*;
import java.nio.file.Path;
import java.util.*;

/**
 * macOS permnorm profiler based on xctrace utility shipped with Xcode Instruments.
 * <p>
 * The profiling process consists of several steps:
 * 1) launching a program that needs to be profiled using `xctrace record` command; in case of success,
 * the output of this step is a "trace-file", which is in fact a directory containing multiple files
 * representing the recorded trace, the trace may contain multiple resulting tables, depending on the template;
 * 2) inspecting a recorded trace to check if it contains a table supported by the profiler; this information
 * could be obtained from the trace's table of contents (`xctrace export --toc`);
 * 3) extracting the table with profiling results from the trace file using xctrace export and parsing it
 * to extract individual samples.
 * <p>
 * `xctrace export` command extracts data only in XML format, thus both the table of contents and the table
 * with profiling results need to be parsed as XML documents.
 * <p>
 * This profiler currently supports only one table type: counters-profile.
 * Such tables generated by the CPU Counters instrument performing sampling either by
 * timer interrupts, or interrupts generated by a PMU counter, depending on particular configuration.
 * <p>
 * Tracing configuration, or template, is required to perform a recording.
 * It is a file that could be configured and saved using Instruments application.
 * <p>
 * CPU Counters template has no default parameters and could only be configured in the Instruments app UI.
 * <p>
 * To provide a default behavior (that does not require a user-configured template) and make profilers use a bit more
 * convenient, XCTraceNormProfiler uses a preconfigured Instruments template ({@code /default.instruments.template.xml})
 * that is configured to sample some PMU counters on timer interrupts.
 * <p>
 */
public class XCTraceNormProfiler implements ExternalProfiler {
    // https://developer.apple.com/documentation/xcode-release-notes/xcode-13-release-notes#Instruments
    // Older versions support CPU Counters in some way, but are lacking handy "counters-profile" table.
    private static final int XCTRACE_VERSION_WITH_COUNTERS_PROFILE_TABLE = 13;
    // Currently, only counters-profile table is supported
    private static final XCTraceTableHandler.ProfilingTableType SUPPORTED_TABLE_TYPE =
            XCTraceTableHandler.ProfilingTableType.COUNTERS_PROFILE;

    private static final String[] CYCLES_EVENT_NAMES = new String[]{
            "CORE_ACTIVE_CYCLE", "Cycles", "FIXED_CYCLES", "CPU_CLK_UNHALTED.THREAD", "CPU_CLK_UNHALTED.THREAD_P"
    };
    private static final String[] INSTRUCTIONS_EVENT_NAMES = new String[]{
            "INST_ALL", "Instructions", "FIXED_INSTRUCTIONS", "INST_RETIRED.ANY", "INST_RETIRED.ANY_P"
    };
    private static final String[] BRANCH_EVENT_NAMES = new String[]{
            "INST_BRANCH", "BR_INST_RETIRED.ALL_BRANCHES", "BR_INST_RETIRED.ALL_BRANCHES_PEBS"
    };
    private static final String[] BRANCH_MISS_EVENT_NAMES = new String[]{
            "BRANCH_MISPRED_NONSPEC", "BR_MISP_RETIRED.ALL_BRANCHES", "BR_MISP_RETIRED.ALL_BRANCHES_PS"
    };

    private final String xctracePath;
    private final String tracingTemplate;
    private final Path temporaryDirectory;
    private final TempFile outputFile;

    private final long delayMs;
    private final long lengthMs;
    private final boolean shouldFixStartTime;

    public XCTraceNormProfiler(String initLine) throws ProfilerException {
        OptionParser parser = new OptionParser();
        parser.formatHelpWith(new ProfilerOptionFormatter(XCTraceNormProfiler.class.getName()));

        OptionSpec<String> templateOpt = parser.accepts("template", "Name of or path to Instruments template. " +
                        "Use `xctrace list templates` to view available templates. " +
                        "Only templates with \"CPU Counters\" instrument are supported at the moment.")
                .withOptionalArg().ofType(String.class);
        OptionSpec<Integer> optDelay = parser.accepts("delay",
                        "Delay collection for a given time, in milliseconds; -1 to detect automatically.")
                .withRequiredArg().ofType(Integer.class).describedAs("ms").defaultsTo(-1);
        OptionSpec<Integer> optLength = parser.accepts("length",
                        "Do the collection for a given time, in milliseconds; -1 to detect automatically.")
                .withRequiredArg().ofType(Integer.class).describedAs("ms").defaultsTo(-1);
        OptionSpec<Boolean> correctOpt = parser.accepts("fixStartTime",
                        "Fix the start time by the time it took to launch.")
                .withRequiredArg().ofType(Boolean.class).defaultsTo(true);

        OptionSet options = ProfilerUtils.parseInitLine(initLine, parser);

        delayMs = options.valueOf(optDelay);
        lengthMs = options.valueOf(optLength);
        shouldFixStartTime = options.valueOf(correctOpt);

        xctracePath = XCTraceSupport.getXCTracePath(XCTRACE_VERSION_WITH_COUNTERS_PROFILE_TABLE);
        if (options.hasArgument(templateOpt)) {
            tracingTemplate = options.valueOf(templateOpt);
        } else {
            tracingTemplate = unpackDefaultTemplate().getAbsolutePath();
        }

        temporaryDirectory = XCTraceSupport.createTemporaryDirectoryName();
        try {
            outputFile = FileUtils.weakTempFile("xctrace-out.xml");
        } catch (IOException e) {
            throw new ProfilerException(e);
        }
    }

    private static File unpackDefaultTemplate() throws ProfilerException {
        try {
            return FileUtils.extractFromResource("/default.instruments.template.xml");
        } catch (IOException e) {
            throw new ProfilerException(e);
        }
    }

    private static XCTraceTableHandler.XCTraceTableDesc findTableDescription(XCTraceTableOfContentsHandler tocHandler) {
        XCTraceTableHandler.XCTraceTableDesc tableDesc = tocHandler.getSupportedTables()
                .stream()
                .filter(t -> t.getTableType() == SUPPORTED_TABLE_TYPE)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Table \"" + SUPPORTED_TABLE_TYPE.tableName +
                        "\" was not found in the trace results."));
        if (tableDesc.getPmcEvents().isEmpty() && tableDesc.getTriggerType() == XCTraceTableHandler.TriggerType.TIME) {
            throw new IllegalStateException("Results does not contain any events.");
        }
        return tableDesc;
    }

    @Override
    public Collection<String> addJVMInvokeOptions(BenchmarkParams params) {
        return XCTraceSupport.recordCommandPrefix(xctracePath, temporaryDirectory.toAbsolutePath().toString(),
                tracingTemplate);
    }

    @Override
    public Collection<String> addJVMOptions(BenchmarkParams params) {
        return Collections.emptyList();
    }

    @Override
    public void beforeTrial(BenchmarkParams benchmarkParams) {
        if (!temporaryDirectory.toFile().isDirectory() && !temporaryDirectory.toFile().mkdirs()) {
            throw new IllegalStateException();
        }
    }

    @Override
    public Collection<? extends Result> afterTrial(BenchmarkResult br, long pid, File stdOut, File stdErr) {
        try {
            return processResults(br);
        } finally {
            XCTraceSupport.removeDirectory(temporaryDirectory);
        }
    }

    private Collection<? extends Result> processResults(BenchmarkResult br) {
        BenchmarkResultMetaData md = br.getMetadata();
        if (md == null) {
            return Collections.emptyList();
        }
        long measurementsDurationMs = md.getStopTime() - md.getMeasurementTime();
        if (measurementsDurationMs == 0L) {
            return Collections.emptyList();
        }
        double opsThroughput = md.getMeasurementOps() / (double) measurementsDurationMs;

        // Find the resulting file and extract metadata from it
        Path traceFile = XCTraceSupport.findTraceFile(temporaryDirectory);
        XCTraceSupport.exportTableOfContents(xctracePath, traceFile.toAbsolutePath().toString(),
                outputFile.getAbsolutePath());

        XCTraceTableOfContentsHandler tocHandler = new XCTraceTableOfContentsHandler();
        tocHandler.parse(outputFile.file());
        // Get info about a table with profiling results
        XCTraceTableHandler.XCTraceTableDesc tableDesc = findTableDescription(tocHandler);
        // Extract profiling results
        XCTraceSupport.exportTable(xctracePath, traceFile.toAbsolutePath().toString(), outputFile.getAbsolutePath(),
                SUPPORTED_TABLE_TYPE);

        // Please refer to XCTraceAsmProfiler::readEvents for detailed explanation,
        // but briefly, ProfilerUtils::measurementDelayMs uses the time when a fork was started,
        // and it's not the actual start time.
        // The actual start time is the time xctrace was launched (tocHandler.getRecordStartMs),
        // and we're correcting measurement delay by the difference between these two timestamps.
        long timeCorrectionMs = 0;
        if (shouldFixStartTime) {
            timeCorrectionMs = tocHandler.getRecordStartMs() - md.getStartTime();
        }
        long skipMs = delayMs;
        if (skipMs == -1L) {
            skipMs = ProfilerUtils.measurementDelayMs(br);
        }
        skipMs -= timeCorrectionMs;
        long durationMs = lengthMs;
        if (durationMs == -1L) {
            durationMs = ProfilerUtils.measuredTimeMs(br);
        }

        long skipNs = skipMs * 1_000_000;
        long durationNs = durationMs * 1_000_000;

        AggregatedEvents aggregator = new AggregatedEvents(tableDesc);
        new XCTraceTableProfileHandler(SUPPORTED_TABLE_TYPE, sample -> {
            if (sample.getTimeFromStartNs() <= skipNs || sample.getTimeFromStartNs() > skipNs + durationNs) {
                return;
            }

            aggregator.add(sample);
        }).parse(outputFile.file());

        if (aggregator.getEventsCount() == 0) {
            return Collections.emptyList();
        }

        Collection<Result<?>> results = new ArrayList<>();
        computeAggregates(results, aggregator);
        aggregator.normalizeByThroughput(opsThroughput);

        for (int i = 0; i < tableDesc.getPmcEvents().size(); i++) {
            String event = tableDesc.getPmcEvents().get(i);
            results.add(new ScalarResult(event, aggregator.getEventValues()[i],
                    "#/op", AggregationPolicy.AVG));
        }
        if (tableDesc.getTriggerType() == XCTraceTableHandler.TriggerType.PMI) {
            results.add(new ScalarResult(tableDesc.triggerEvent(),
                    aggregator.getEventValues()[aggregator.getEventValues().length - 1],
                    "#/op", AggregationPolicy.AVG));
        }
        return results;
    }

    private void computeAggregates(Collection<Result<?>> results, AggregatedEvents aggregator) {
        computeCommonMetrics(results, aggregator);

        if (System.getProperty("os.arch").equals("aarch64")) {
            computeAppleSiliconArm64InstDensityMetrics(results, aggregator);
        }
    }

    private static void computeCommonMetrics(Collection<Result<?>> results, AggregatedEvents aggregator) {
        CounterValue cycles = aggregator.getAnyOfOrNull(CYCLES_EVENT_NAMES);
        CounterValue insts = aggregator.getAnyOfOrNull(INSTRUCTIONS_EVENT_NAMES);

        if (cycles != null && cycles.getValue() != 0D && insts != null && insts.getValue() != 0D) {
            results.add(new ScalarResult("CPI", cycles.getValue() / insts.getValue(),
                    cycles.getName() + "/" + insts.getName(), AggregationPolicy.AVG));
            results.add(new ScalarResult("IPC", insts.getValue() / cycles.getValue(),
                    insts.getName() + "/" + cycles.getName(), AggregationPolicy.AVG));
        }

        CounterValue branches = aggregator.getAnyOfOrNull(BRANCH_EVENT_NAMES);
        CounterValue missedBranches = aggregator.getAnyOfOrNull(BRANCH_MISS_EVENT_NAMES);
        if (branches != null && branches.getValue() != 0D && missedBranches != null) {
            results.add(new ScalarResult("Branch miss ratio", missedBranches.getValue() / branches.getValue(),
                    missedBranches.getName() + "/" + branches.getName(), AggregationPolicy.AVG));
        }
    }

    // Compute instructions density metrics (defined in Apple Silicon CPU Optimization Guide,
    // https://developer.apple.com/documentation/apple-silicon/cpu-optimization-guide).
    private static void computeAppleSiliconArm64InstDensityMetrics(Collection<Result<?>> results, AggregatedEvents aggregator) {
        CounterValue insts = aggregator.getAnyOfOrNull(INSTRUCTIONS_EVENT_NAMES);
        if (insts == null) {
            return;
        }
        for (String eventName : aggregator.getEventNames()) {
            if (!eventName.startsWith("INST_") || eventName.equals("INST_ALL")) {
                continue;
            }
            Double value = aggregator.getCountOrNull(eventName);
            if (value == null || value == 0D) {
                continue;
            }

            results.add(new ScalarResult(eventName + " density (of instructions)", value / insts.getValue(),
                    eventName + "/" + insts.getName(), AggregationPolicy.AVG));
        }
    }

    @Override
    public boolean allowPrintOut() {
        return true;
    }

    @Override
    public boolean allowPrintErr() {
        return false;
    }

    @Override
    public String getDescription() {
        return "macOS xctrace (Instruments) PMU counter statistics, normalized by operation count";
    }

    private static class AggregatedEvents {
        private final List<String> eventNames;
        private final double[] eventValues;
        private long eventsCount = 0;

        private long minTimestampMs = Long.MAX_VALUE;
        private long maxTimestampMs = Long.MIN_VALUE;

        AggregatedEvents(XCTraceTableHandler.XCTraceTableDesc tableDesc) {
            List<String> names = new ArrayList<>(tableDesc.getPmcEvents());
            names.add(tableDesc.triggerEvent());
            eventNames = Collections.unmodifiableList(names);
            eventValues = new double[getEventNames().size()];
        }

        void add(XCTraceTableProfileHandler.XCTraceSample sample) {
            long[] counters = sample.getPmcValues();
            for (int i = 0; i < counters.length; i++) {
                eventValues[i] += counters[i];
            }
            eventValues[eventValues.length - 1] = sample.getWeight();
            minTimestampMs = Math.min(minTimestampMs, sample.getTimeFromStartNs());
            maxTimestampMs = Math.max(maxTimestampMs, sample.getTimeFromStartNs());
            eventsCount += 1;
        }

        void normalizeByThroughput(double throughput) {
            if (maxTimestampMs == minTimestampMs) {
                throw new IllegalStateException("Min and max timestamps are the same.");
            }
            double timeSpanMs = (maxTimestampMs - minTimestampMs) / 1e6;
            for (int i = 0; i < getEventValues().length; i++) {
                eventValues[i] = eventValues[i] / timeSpanMs / throughput;
            }
        }

        CounterValue getAnyOfOrNull(String... keys) {
            for (String key : keys) {
                Double value = getCountOrNull(key);
                if (value != null) {
                    return new CounterValue(key, value);
                }
            }
            return null;
        }

        Double getCountOrNull(String event) {
            int idx = eventNames.indexOf(event);
            if (idx == -1) return null;
            return eventValues[idx];
        }

        List<String> getEventNames() {
            return eventNames;
        }

        double[] getEventValues() {
            return eventValues;
        }

        long getEventsCount() {
            return eventsCount;
        }
    }

    private static class CounterValue {
        private final String name;
        private final double value;

        CounterValue(String name, double value) {
            this.name = name;
            this.value = value;
        }

        double getValue() {
            return value;
        }

        String getName() {
            return name;
        }
    }
}
