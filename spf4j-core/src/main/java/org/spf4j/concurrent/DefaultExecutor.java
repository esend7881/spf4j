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
package org.spf4j.concurrent;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.spf4j.base.AbstractRunnable;
import static org.spf4j.base.Runtime.WAIT_FOR_SHUTDOWN_MILLIS;

/**
 *
 * @author zoly
 */
public final class DefaultExecutor {

    private DefaultExecutor() {
    }

    static {
        org.spf4j.base.Runtime.queueHookAtEnd(new AbstractRunnable(true) {

            @Override
            public void doRun() throws InterruptedException {
                shutdown();
                INSTANCE.awaitTermination(WAIT_FOR_SHUTDOWN_MILLIS, TimeUnit.MILLISECONDS);
                List<Runnable> remaining = INSTANCE.shutdownNow();
                if (remaining.size() > 0) {
                    System.err.println("Remaining tasks: " + remaining);
                }
            }
        });
    }

    public static final ExecutorService INSTANCE =
            new ThreadPoolExecutor(Integer.getInteger("default.executor.coreThreads", 0), Integer.MAX_VALUE,
            60L, TimeUnit.SECONDS,
            new SynchronousQueue<Runnable>(),
            new CustomThreadFactory("DefaultExecutor", false));

    public static void shutdown() {
        INSTANCE.shutdown();
    }
}
