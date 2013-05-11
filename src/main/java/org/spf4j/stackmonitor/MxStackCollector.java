/*
 * Copyright (c) 2001, Zoltan Farkas All Rights Reserved.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package org.spf4j.stackmonitor;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;

/**
 *
 * @author zoly
 */
public final class MxStackCollector extends AbstractStackCollector {

    private final ThreadMXBean threadMX = ManagementFactory.getThreadMXBean();

    @Override
    public void sample() {
        ThreadInfo[] stackDump = threadMX.dumpAllThreads(true, true);
        recordStackDump(stackDump);
    }

    private void recordStackDump(final ThreadInfo[] stackDump) {
        for (ThreadInfo entry : stackDump) {
            StackTraceElement[] stackTrace = entry.getStackTrace();
            if (stackTrace.length > 0 && !(entry.getThreadId() == Thread.currentThread().getId())) {
                addSample(stackTrace);
            }
        }
    }
}
