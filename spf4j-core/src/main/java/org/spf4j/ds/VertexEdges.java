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
package org.spf4j.ds;

import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import org.spf4j.base.Pair;

/**
 *
 * @author zoly
 */
@ParametersAreNonnullByDefault
public final class VertexEdges<V, E> extends Pair<Map<E, V>, Map<E, V>> {

    public VertexEdges(final Map<E, V> first, final Map<E, V> second) {
        super(first, second);
    }
    
    public VertexEdges() {
        super(new HashMap<E, V>(2), new HashMap<E, V>(2));
    }
  
    @Nonnull
    public Map<E, V> getIncomming() {
        return first;
    }
    

    @Nonnull
    public Map<E, V> getOutgoing() {
        return second;
    }

    
}