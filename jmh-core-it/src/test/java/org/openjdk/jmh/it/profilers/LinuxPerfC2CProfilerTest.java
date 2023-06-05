/*
 * Copyright Amazon.com Inc. or its affiliates. All Rights Reserved.
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
package org.openjdk.jmh.it.profilers;

import org.junit.Assert;
import org.junit.Test;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.it.Fixtures;
import org.openjdk.jmh.profile.LinuxPerfC2CProfiler;
import org.openjdk.jmh.profile.ProfilerException;
import org.openjdk.jmh.results.Result;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.Map;
import java.util.concurrent.TimeUnit;

@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class LinuxPerfC2CProfilerTest {

    @Benchmark
    public void work() {
        somethingInTheMiddle();
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public void somethingInTheMiddle() {
        Fixtures.work();
    }

    @Test
    public void test() throws RunnerException {
        try {
            new LinuxPerfC2CProfiler();
        } catch (ProfilerException e) {
            System.out.println("Profiler is not supported or cannot be enabled, skipping test");
            return;
        }

        Options opts = new OptionsBuilder()
                .include(Fixtures.getTestMask(this.getClass()))
                .addProfiler(LinuxPerfC2CProfiler.class)
                .build();

        RunResult rr = new Runner(opts).runSingle();

        Map<String, Result> sr = rr.getSecondaryResults();
        String text = ProfilerTestUtils.checkedGet(sr, "·perfc2c").extendedInfo();

        Assert.assertNotNull(text);
    }

}
