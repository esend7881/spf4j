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
import java.util.Set;
import org.spf4j.base.Pair;

/**
 *
 * @author zoly
 */
public final class HashMapGraph<V, E> implements Graph<V, E> {

    private Map<E, Pair<V, V>> edgeNodes;
    private Map<V, VertexEdges> vertices;
    
    public HashMapGraph() {
        edgeNodes = new HashMap<E, Pair<V, V>>();
        vertices = new HashMap<V, VertexEdges>();
    }
    
    public void add(final V vertex) {
        if (!vertices.containsKey(vertex)) {
            vertices.put(vertex, null);
        }
    }
    
    public void add(final E edge, final V fromVertex, final V toVertex) {
        edgeNodes.put(edge, new Pair<V, V>(fromVertex, toVertex));
        VertexEdges fromV = vertices.get(fromVertex);
        if (fromV == null) {
            fromV = new VertexEdges();
            vertices.put(fromVertex, fromV);
        }
        fromV.getOutgoing().put(edge, toVertex);
        
        VertexEdges toV = vertices.get(toVertex);
        if (toV == null) {
            toV = new VertexEdges();
            vertices.put(toVertex, toV);
        }
        toV.getIncomming().put(edge, fromVertex);
    }
    

    @Override
    public Pair<V, V> getVertices(final E edge) {
        return edgeNodes.get(edge);
    }

    @Override
    public VertexEdges<V, E> getEdges(final V vertice) {
        return vertices.get(vertice);
    }

    @Override
    public Set<V> getVertices() {
        return vertices.keySet();
    }
    
}