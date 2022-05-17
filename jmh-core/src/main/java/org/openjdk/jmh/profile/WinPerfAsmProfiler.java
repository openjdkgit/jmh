/*
 * Copyright (c) 2014, 2014, Oracle and/or its affiliates. All rights reserved.
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

import joptsimple.OptionException;
import joptsimple.OptionParser;
import joptsimple.OptionSpec;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.results.BenchmarkResult;
import org.openjdk.jmh.results.Result;
import org.openjdk.jmh.util.*;

import java.io.*;
import java.util.*;

/**
 * Windows performance profiler based on "xperf" utility.
 * <p>
 * You must install {@code Windows Performance Toolkit}. Once installed, locate directory with {@code xperf.exe}
 * file and either add it to {@code PATH} environment variable, or assign it to {@code xperf.dir} parameter.
 * <p>
 * This profiler counts only {@code SampledProfile} events. To achieve this, we set {@code xperf} providers to
 * {@code loader+proc_thread+profile}. You may optionally save {@code xperf} binary or parsed outputs using
 * {@code savePerfBin} or {@code savePerf} parameters respectively. If you do so and
 * want to log more events, you can use {@code xperf.providers} parameter to override providers.
 * However, you must specify {@code loader}, {@code proc_thread} and {@code profile} providers anyway. Otherwise
 * sample events will not be generated and profiler will show nothing.
 * <p>
 * By default JDK distributive do not have debug symbols. If you want to analyze JVM internals, you must build OpenJDK
 * on your own. Once built, go to {@code bin/server} directory and unpack {@code jvm.diz}. Now you have {@code jvm.pdb}
 * file with JVM debug symbols. Finally, you must assign debug symbols directory to {@code symbol.dir} parameter.
 * <p>
 * This profiler behaves differently comparing to it's Linux counterpart {@link LinuxPerfAsmProfiler}. Linux profiler
 * employs {@code perf} utility which can be used to profile a single process. Therefore, Linux profiler wraps forked
 * JVM command line. In contrast, {@code xperf} cannot profile only a single process. It have {@code -PidNewProcess}
 * argument, but it's sole purpose is to start profiling before the process is started, so that one can be sure that
 * none events generated by this process are missed. It does not filter events from other processes anyhow. For this
 * reason, this profiler doesn't alter forked JVM startup command. Instead, it starts {@code xperf} recording in
 * {@link #beforeTrial(BenchmarkParams)} method, and stops in {@link ExternalProfiler#afterTrial(org.openjdk.jmh.results.BenchmarkResult, long, java.io.File, java.io.File)}. This
 * leaves possibility to run this profiler in conjunction with some other profiler requiring startup command
 * alteration.
 * <p>
 * For this reason the profiler must know PID of forked JVM process.
 */
public class WinPerfAsmProfiler extends AbstractPerfAsmProfiler {

    private static final String MSG_UNABLE_START = "Unable to start the profiler. Try running JMH as Administrator, " +
            "and ensure that previous profiling session is stopped. Use 'xperf -stop' to stop the active profiling session.";
    private static final String MSG_UNABLE_STOP  = "Unable to stop the profiler. Please try running JMH as Administrator.";

    private final String xperfProviders;
    private final String symbolDir;
    private final String path;

    /** PID. */
    private volatile String pid;
    private OptionSpec<String> optXperfDir;
    private OptionSpec<String> optXperfProviders;
    private OptionSpec<String> optSymbolDir;

    public WinPerfAsmProfiler(String initLine) throws ProfilerException {
        super(initLine, "SampledProfile");

        try {
            String xperfDir = set.valueOf(optXperfDir);
            xperfProviders = set.valueOf(optXperfProviders);
            symbolDir = set.valueOf(optSymbolDir);
            path = xperfDir != null && !xperfDir.isEmpty() ? xperfDir + File.separatorChar + "xperf" : "xperf";
        } catch (OptionException e) {
            throw new ProfilerException(e.getMessage());
        }

        Collection<String> errsOn = Utils.tryWith(path, "-on", xperfProviders);
        if (!errsOn.isEmpty()) {
            if (errsOn instanceof ArrayList) {
                errsOn.add(MSG_UNABLE_START);
            }
            throw new ProfilerException(errsOn.toString());
        }

        Collection<String> errsStop = Utils.tryWith(path, "-stop");
        if (!errsStop.isEmpty()) {
            if (errsStop instanceof ArrayList) {
                errsStop.add(MSG_UNABLE_STOP);
            }
            throw new ProfilerException(errsStop.toString());
        }
    }

    @Override
    protected void addMyOptions(OptionParser parser) {
        optXperfDir = parser.accepts("xperf.dir",
                "Path to \"xperf\" installation directory. Empty by default, so that xperf is expected to be in PATH.")
                .withRequiredArg().ofType(String.class).describedAs("path");
        optXperfProviders = parser.accepts("xperf.providers",
                "xperf providers to use.")
                .withRequiredArg().ofType(String.class).describedAs("string").defaultsTo("loader+proc_thread+profile");
        optSymbolDir = parser.accepts("symbol.dir",
                "Path to a directory with jvm.dll symbols (optional).")
                .withRequiredArg().ofType(String.class).describedAs("string");
    }

    @Override
    public Collection<String> addJVMInvokeOptions(BenchmarkParams params) {
        // "xperf" cannot be started to track particular process as "perf" in Linux does.
        // Therefore we do not alter JVM invoke options anyhow. Instead, profiler will be started
        // during "before-trial" stage.
        return Collections.emptyList();
    }

    @Override
    public void beforeTrial(BenchmarkParams params) {
        // Start profiler before forked JVM is started.
        Collection<String> errs = Utils.tryWith(path, "-on", xperfProviders);

        if (!errs.isEmpty()) {
            if (errs instanceof ArrayList) {
                errs.add(MSG_UNABLE_START);
            }
            throw new IllegalStateException(errs.toString());
        }
    }

    @Override
    public Collection<? extends Result> afterTrial(BenchmarkResult br, long pid, File stdOut, File stdErr) {
        if (pid == 0) {
            throw new IllegalStateException("perfasm needs the forked VM PID, but it is not initialized.");
        }
        this.pid = String.valueOf(pid);
        return super.afterTrial(br, pid, stdOut, stdErr);
    }

    @Override
    public String getDescription() {
        return "Windows xperf + PrintAssembly Profiler";
    }

    @Override
    protected void parseEvents() {
        // 1. Stop profiling by calling xperf dumper.
        Collection<String> errs = Utils.tryWith(path, "-d", perfBinData.getAbsolutePath());

        if (!errs.isEmpty()) {
            if (errs instanceof ArrayList) {
                errs.add(MSG_UNABLE_STOP);
            }
            throw new IllegalStateException(errs.toString());
        }

        // 2. Convert binary data to text form.
        try {
            ProcessBuilder pb = new ProcessBuilder(path, "-i", perfBinData.getAbsolutePath(), "-symbols", "-a", "dumper");
            if (symbolDir != null) {
                pb.environment().put("_NT_SYMBOL_PATH", symbolDir);
            }

            Process p = pb.start();

            FileOutputStream fos = new FileOutputStream(perfParsedData.file());

            InputStreamDrainer errDrainer = new InputStreamDrainer(p.getErrorStream(), fos);
            InputStreamDrainer outDrainer = new InputStreamDrainer(p.getInputStream(), fos);

            errDrainer.start();
            outDrainer.start();

            p.waitFor();

            errDrainer.join();
            outDrainer.join();
        } catch (IOException | InterruptedException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Override
    protected PerfEvents readEvents(double skipMs, double lenMs) {
        double readFrom = skipMs / 1000D;
        double readTo = (skipMs + lenMs) / 1000D;

        try (FileReader fr = new FileReader(perfParsedData.file());
             BufferedReader reader = new BufferedReader(fr)) {
            Deduplicator<MethodDesc> dedup = new Deduplicator<>();

            Multimap<MethodDesc, Long> methods = new HashMultimap<>();
            Map<String, Multiset<Long>> events = new LinkedHashMap<>();
            for (String evName : requestedEventNames) {
                events.put(evName, new TreeMultiset<Long>());
            }

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();

                String[] elems = line.split(",\\s+");

                String evName = elems[0].trim();

                // We work with only one event type - "SampledProfile".
                if (!requestedEventNames.get(0).equals(evName))
                    continue;

                // Check PID.
                String pidStr = elems[2].trim();

                int pidOpenIdx = pidStr.indexOf("(");
                int pidCloseIdx = pidStr.indexOf(")");

                if (pidOpenIdx == -1 || pidCloseIdx == -1 || pidCloseIdx < pidOpenIdx)
                    continue; // Malformed PID, probably this is the header.

                pidStr = pidStr.substring(pidOpenIdx + 1, pidCloseIdx).trim();

                if (!pid.equals(pidStr))
                    continue;

                // Check timestamp
                String timeStr = elems[1].trim();

                double time = Double.parseDouble(timeStr) / 1_000_000;

                if (time < readFrom)
                    continue;
                if (time > readTo)
                    continue;

                // Get address.
                String addrStr = elems[4].trim().replace("0x", "");

                // Get lib and function name.
                String libSymStr = elems[7].trim();

                String lib = libSymStr.substring(0, libSymStr.indexOf('!'));
                String symbol = libSymStr.substring(libSymStr.indexOf('!') + 1);

                Multiset<Long> evs = events.get(evName);

                assert evs != null;

                try {
                    Long addr = Long.parseLong(addrStr, 16);
                    evs.add(addr);
                    methods.put(dedup.dedup(MethodDesc.nativeMethod(symbol, lib)), addr);
                } catch (NumberFormatException e) {
                    // kernel addresses like "ffffffff810c1b00" overflow signed long,
                    // record them as dummy address
                    evs.add(0L);
                }
            }

            IntervalMap<MethodDesc> methodMap = new IntervalMap<>();
            for (MethodDesc md : methods.keys()) {
                Collection<Long> longs = methods.get(md);
                methodMap.add(md, Utils.min(longs), Utils.max(longs));
            }

            return new PerfEvents(requestedEventNames, events, methodMap);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    protected String perfBinaryExtension() {
        // Files with ".etl" extension can be opened by "Windows Performance Analyzer" right away.
        return ".etl";
    }
}
